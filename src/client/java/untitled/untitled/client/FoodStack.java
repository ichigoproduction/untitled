package untitled.untitled.client;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.regex.Pattern;

public final class FoodStack {
    private static final int DOTS = 3;
    private static final int TRIGGER_EVERY = 4;

    private static final int SEGMENT_WIDTH = 10;
    private static final int SEGMENT_HEIGHT = 4;
    private static final int SEGMENT_GAP = 4;
    private static final int DEFAULT_Y_FROM_CROSSHAIR = 18;
    private static final int SCREEN_MARGIN = 2;

    private static final int EMPTY_BACKGROUND = 0x44000000;
    private static final int EMPTY_EDGE = 0x66000000;
    private static final int FILLED_BACKGROUND = 0xFFFF4DA6;
    private static final int FILLED_HIGHLIGHT = 0x99FFFFFF;

    private static final long LOBBY_RESET_DELAY_MS = 180L;
    private static final long LOBBY_RETRY_WINDOW_MS = 5_000L;
    private static final long LOBBY_UNDO_WINDOW_MS = 5_000L;

    private static final Pattern RETRY_PATTERN = Pattern.compile(
            "(?:\\d+\\s*초\\s*후\\s*)?재\\s*시도\\s*하\\s*십시오\\.?"
    );
    private static final Pattern MINECRAFT_FORMAT = Pattern.compile("§[0-9A-FK-ORa-fk-or]");

    private static boolean initialized = false;
    private static boolean enabled = true;
    private static boolean verticalLayout = false;

    private static int offsetX = 0;
    private static int offsetY = 0;
    private static int stack = 0;

    private static String lastSignal = "";
    private static long dedupeUntilMs = 0L;
    private static RegistryKey<World> lastWorldKey = null;
    private static long eatSerial = 0L;

    private static long lastLobbyCommandMs = 0L;
    private static boolean lobbyResetPending = false;
    private static long lobbyResetDueMs = 0L;
    private static boolean lobbyUndoArmed = false;
    private static long lobbyUndoDeadlineMs = 0L;

    private static int snapshotStack = 0;
    private static String snapshotLastSignal = "";
    private static long snapshotDedupeUntilMs = 0L;
    private static long snapshotEatSerial = 0L;

    private static KeyBinding toggleKey;
    private static EditHud.HudBounds lastEditorBounds =
            new EditHud.HudBounds(0, 0, 1, 1);

    private FoodStack() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "Toggle FoodStack",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "untitled"
        ));

        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, timestamp) ->
                        onIncomingMessage(message.getString())
        );
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> onIncomingMessage(message.getString())
        );
        ClientSendMessageEvents.COMMAND.register(FoodStack::onOutgoingCommand);

        HudRenderCallback.EVENT.register((context, tickCounter) -> renderHud(context));
        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> registerCommands(dispatcher)
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tickWorldChangeReset(client);

            if (client == null) {
                return;
            }

            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                saveSettings();
            }

            processLobbyResetTimers();
        });
    }

    static int getOffsetX() {
        return offsetX;
    }

    static int getOffsetY() {
        return offsetY;
    }

    static void setOffsets(int x, int y) {
        offsetX = x;
        offsetY = y;
        clampOffsetsToScreen(MinecraftClient.getInstance());
    }

    static void moveBy(int deltaX, int deltaY) {
        offsetX += deltaX;
        offsetY += deltaY;
        clampOffsetsToScreen(MinecraftClient.getInstance());
    }

    static void resetPosition() {
        offsetX = 0;
        offsetY = 0;
    }

    static EditHud.HudBounds getEditorBounds() {
        return lastEditorBounds;
    }

    static void renderEditorPreview(DrawContext context) {
        lastEditorBounds = renderSegments(context, DOTS);
    }

    static void writeSettings(JsonObject root) {
        root.addProperty("foodStackEnabled", enabled);
        root.addProperty("foodStackVertical", verticalLayout);
        root.addProperty("foodStackOffsetX", offsetX);
        root.addProperty("foodStackOffsetY", offsetY);
    }

    static void readSettings(JsonObject root) {
        if (root.has("foodStackEnabled")) {
            enabled = root.get("foodStackEnabled").getAsBoolean();
        }
        if (root.has("foodStackVertical")) {
            verticalLayout = root.get("foodStackVertical").getAsBoolean();
        }

        int savedX = root.has("foodStackOffsetX")
                ? root.get("foodStackOffsetX").getAsInt()
                : 0;
        int savedY = root.has("foodStackOffsetY")
                ? root.get("foodStackOffsetY").getAsInt()
                : 0;

        offsetX = savedX;
        offsetY = savedY;
    }

    private static void saveSettings() {
        EditHud.saveSettings();
    }

    private static void registerCommands(
            CommandDispatcher<FabricClientCommandSource> dispatcher
    ) {
        registerRootCommand(dispatcher, "foodstack");
        registerRootCommand(dispatcher, "fs");
    }

    private static void registerRootCommand(
            CommandDispatcher<FabricClientCommandSource> dispatcher,
            String root
    ) {
        dispatcher.register(
                ClientCommandManager.literal(root)
                        .executes(context -> 1)
                        .then(ClientCommandManager.literal("reset")
                                .executes(context -> {
                                    manualReset();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("toggle")
                                .executes(context -> {
                                    enabled = !enabled;
                                    saveSettings();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("horizontal")
                                .executes(context -> {
                                    verticalLayout = false;
                                    clampOffsetsToScreen(MinecraftClient.getInstance());
                                    saveSettings();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("vertical")
                                .executes(context -> {
                                    verticalLayout = true;
                                    clampOffsetsToScreen(MinecraftClient.getInstance());
                                    saveSettings();
                                    return 1;
                                }))
        );
    }

    private static void onOutgoingCommand(String commandWithoutSlash) {
        if (commandWithoutSlash == null) {
            return;
        }

        String normalized = commandWithoutSlash.trim();
        if (normalized.isEmpty()) {
            return;
        }

        int space = normalized.indexOf(' ');
        String commandName = space >= 0
                ? normalized.substring(0, space)
                : normalized;

        String lower = commandName.toLowerCase(Locale.ROOT);
        if (lower.equals("lobby") || lower.equals("fhql") || commandName.equals("로비")) {
            scheduleLobbyReset();
        }
    }

    private static void scheduleLobbyReset() {
        long now = System.currentTimeMillis();

        snapshotStack = stack;
        snapshotLastSignal = lastSignal;
        snapshotDedupeUntilMs = dedupeUntilMs;
        snapshotEatSerial = eatSerial;

        lastLobbyCommandMs = now;
        lobbyResetPending = true;
        lobbyResetDueMs = now + LOBBY_RESET_DELAY_MS;

        lobbyUndoArmed = false;
        lobbyUndoDeadlineMs = 0L;
    }

    private static void processLobbyResetTimers() {
        long now = System.currentTimeMillis();

        if (lobbyUndoArmed && now > lobbyUndoDeadlineMs) {
            lobbyUndoArmed = false;
        }

        if (lobbyResetPending && now >= lobbyResetDueMs) {
            resetCore();
            lobbyResetPending = false;
            lobbyUndoArmed = true;
            lobbyUndoDeadlineMs = now + LOBBY_UNDO_WINDOW_MS;
        }

        if (lastLobbyCommandMs != 0L
                && now - lastLobbyCommandMs > LOBBY_RETRY_WINDOW_MS + 2_000L) {
            lobbyResetPending = false;
            lobbyUndoArmed = false;
            lastLobbyCommandMs = 0L;
        }
    }

    private static void onIncomingMessage(String raw) {
        String clean = normalize(raw);
        handleRetryMessage(clean);

        if (clean.contains("먹은 음식:")) {
            onFoodMessage(clean);
        }
    }

    private static void handleRetryMessage(String clean) {
        long now = System.currentTimeMillis();

        if (lastLobbyCommandMs == 0L
                || now - lastLobbyCommandMs > LOBBY_RETRY_WINDOW_MS
                || !RETRY_PATTERN.matcher(clean).find()) {
            return;
        }

        if (lobbyResetPending) {
            lobbyResetPending = false;
            return;
        }

        if (lobbyUndoArmed && now <= lobbyUndoDeadlineMs) {
            if (eatSerial == snapshotEatSerial) {
                restoreSnapshot();
            }
            lobbyUndoArmed = false;
        }
    }

    private static void onFoodMessage(String raw) {
        int index = raw.indexOf("먹은 음식:");
        if (index < 0) {
            return;
        }

        String food = raw.substring(index + "먹은 음식:".length()).trim();
        if (food.isEmpty()) {
            food = "?";
        }

        String signal = "EAT|" + food;
        long now = System.currentTimeMillis();

        if (signal.equals(lastSignal) && now < dedupeUntilMs) {
            return;
        }

        lastSignal = signal;
        dedupeUntilMs = now + 180L;
        eatSerial++;

        int next = stack + 1;
        stack = next >= TRIGGER_EVERY
                ? 0
                : Math.min(next, DOTS);
    }

    private static void tickWorldChangeReset(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            lastWorldKey = null;
            lobbyResetPending = false;
            lobbyUndoArmed = false;
            lastLobbyCommandMs = 0L;
            return;
        }

        clampOffsetsToScreen(client);

        RegistryKey<World> currentWorld = client.world.getRegistryKey();
        if (lastWorldKey == null || !lastWorldKey.equals(currentWorld)) {
            lastWorldKey = currentWorld;
            manualReset();
        }
    }

    public static void manualReset() {
        lobbyResetPending = false;
        lobbyUndoArmed = false;
        lastLobbyCommandMs = 0L;
        resetCore();
    }

    private static void resetCore() {
        stack = 0;
        lastSignal = "";
        dedupeUntilMs = 0L;
    }

    private static void restoreSnapshot() {
        stack = snapshotStack;
        lastSignal = snapshotLastSignal;
        dedupeUntilMs = snapshotDedupeUntilMs;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }

        String normalized = MINECRAFT_FORMAT.matcher(value).replaceAll("");
        normalized = normalized.replace('\u00A0', ' ');
        return normalized.trim().replaceAll("\\s+", " ");
    }

    private static void renderHud(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null
                || client.player == null
                || client.world == null
                || client.options.hudHidden
                || client.currentScreen instanceof EditHud
                || !enabled) {
            return;
        }

        renderSegments(context, stack);
    }

    private static EditHud.HudBounds renderSegments(
            DrawContext context,
            int filledSegments
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return new EditHud.HudBounds(0, 0, 1, 1);
        }

        clampOffsetsToScreen(client);
        LayoutBox box = computeLayout(client, offsetX, offsetY, verticalLayout);

        for (int index = 0; index < DOTS; index++) {
            int x;
            int y;

            if (verticalLayout) {
                x = box.startX;
                y = box.startY + (DOTS - 1 - index) * (SEGMENT_HEIGHT + SEGMENT_GAP);
            } else {
                x = box.startX + index * (SEGMENT_WIDTH + SEGMENT_GAP);
                y = box.startY;
            }

            int x2 = x + SEGMENT_WIDTH;
            int y2 = y + SEGMENT_HEIGHT;
            boolean filled = index < filledSegments;

            context.fill(x - 1, y - 1, x2 + 1, y2 + 1, EMPTY_EDGE);
            context.fill(x, y, x2, y2, filled ? FILLED_BACKGROUND : EMPTY_BACKGROUND);

            if (filled) {
                context.fill(x, y, x2, y + 1, FILLED_HIGHLIGHT);
            }
        }

        return new EditHud.HudBounds(
                box.startX,
                box.startY,
                box.totalWidth,
                box.totalHeight
        );
    }

    private static LayoutBox computeLayout(
            MinecraftClient client,
            int horizontalOffset,
            int verticalOffset,
            boolean vertical
    ) {
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int centerX = screenWidth / 2 + horizontalOffset;
        int centerY = screenHeight / 2 + verticalOffset + DEFAULT_Y_FROM_CROSSHAIR;

        if (vertical) {
            int totalHeight = DOTS * SEGMENT_HEIGHT + (DOTS - 1) * SEGMENT_GAP;
            return new LayoutBox(
                    centerX - SEGMENT_WIDTH / 2,
                    centerY,
                    SEGMENT_WIDTH,
                    totalHeight
            );
        }

        int totalWidth = DOTS * SEGMENT_WIDTH + (DOTS - 1) * SEGMENT_GAP;
        return new LayoutBox(
                centerX - totalWidth / 2,
                centerY,
                totalWidth,
                SEGMENT_HEIGHT
        );
    }

    private static void clampOffsetsToScreen(MinecraftClient client) {
        if (client == null) {
            return;
        }

        LayoutBox box = computeLayout(client, offsetX, offsetY, verticalLayout);
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        if (box.startX < SCREEN_MARGIN) {
            offsetX += SCREEN_MARGIN - box.startX;
        }
        if (box.startX + box.totalWidth > screenWidth - SCREEN_MARGIN) {
            offsetX -= box.startX + box.totalWidth - (screenWidth - SCREEN_MARGIN);
        }
        if (box.startY < SCREEN_MARGIN) {
            offsetY += SCREEN_MARGIN - box.startY;
        }
        if (box.startY + box.totalHeight > screenHeight - SCREEN_MARGIN) {
            offsetY -= box.startY + box.totalHeight - (screenHeight - SCREEN_MARGIN);
        }
    }

    private record LayoutBox(
            int startX,
            int startY,
            int totalWidth,
            int totalHeight
    ) {
    }
}
