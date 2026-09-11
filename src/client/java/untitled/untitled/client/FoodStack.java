package untitled.untitled.client;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.Locale;
import java.util.regex.Pattern;

public final class FoodStack {
    private static final int FOOD_SEGMENTS = 3;
    private static final int FOOD_TRIGGER_EVERY = 4;
    private static final int DRINK_SEGMENTS = 1;
    private static final int DRINK_TRIGGER_EVERY = 2;

    private static final int SEGMENT_WIDTH = 10;
    private static final int SEGMENT_HEIGHT = 4;
    private static final int SEGMENT_GAP = 4;
    private static final int DEFAULT_Y_FROM_CROSSHAIR = 18;
    private static final int DEFAULT_DRINK_OFFSET_Y = 10;
    private static final int SCREEN_MARGIN = 2;

    private static final int EMPTY_BACKGROUND = 0x44000000;
    private static final int EMPTY_EDGE = 0x66000000;
    private static final int FOOD_FILLED_BACKGROUND = 0xFFFF4DA6;
    private static final int DRINK_FILLED_BACKGROUND = 0xFF69C9FF;
    private static final int FILLED_HIGHLIGHT = 0x99FFFFFF;

    private static final long DEDUPE_WINDOW_MS = 180L;
    private static final long CONSUMPTION_CLASSIFY_WINDOW_MS = 1_000L;
    private static final long LOBBY_RESET_DELAY_MS = 180L;
    private static final long LOBBY_RETRY_WINDOW_MS = 5_000L;
    private static final long LOBBY_UNDO_WINDOW_MS = 5_000L;

    private static final Pattern RETRY_PATTERN = Pattern.compile(
            "(?:\\d+\\s*초\\s*후\\s*)?재\\s*시도\\s*하\\s*십시오\\.?"
    );
    private static final Pattern MINECRAFT_FORMAT = Pattern.compile("§[0-9A-FK-ORa-fk-or]");

    private enum ConsumptionType {
        FOOD,
        DRINK
    }

    private static boolean initialized = false;
    private static boolean foodEnabled = true;
    private static boolean drinkEnabled = true;
    private static boolean foodVerticalLayout = false;

    private static int foodOffsetX = 0;
    private static int foodOffsetY = 0;
    private static int drinkOffsetX = 0;
    private static int drinkOffsetY = DEFAULT_DRINK_OFFSET_Y;

    private static int foodStack = 0;
    private static int drinkStack = 0;

    private static String lastSignal = "";
    private static long dedupeUntilMs = 0L;
    private static String pendingConsumptionSignal = "";
    private static long pendingConsumptionUntilMs = 0L;
    private static RegistryKey<World> lastWorldKey = null;
    private static long consumptionSerial = 0L;

    private static long lastLobbyCommandMs = 0L;
    private static boolean lobbyResetPending = false;
    private static long lobbyResetDueMs = 0L;
    private static boolean lobbyUndoArmed = false;
    private static long lobbyUndoDeadlineMs = 0L;

    private static int snapshotFoodStack = 0;
    private static int snapshotDrinkStack = 0;
    private static String snapshotLastSignal = "";
    private static long snapshotDedupeUntilMs = 0L;
    private static long snapshotConsumptionSerial = 0L;

    private static EditHud.HudBounds lastFoodEditorBounds =
            new EditHud.HudBounds(0, 0, 1, 1);
    private static EditHud.HudBounds lastDrinkEditorBounds =
            new EditHud.HudBounds(0, 0, 1, 1);

    private FoodStack() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

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
            processLobbyResetTimers();
            processPendingConsumptionTimer();
        });
    }

    static int getFoodOffsetX() {
        return foodOffsetX;
    }

    static int getFoodOffsetY() {
        return foodOffsetY;
    }

    static int getDrinkOffsetX() {
        return drinkOffsetX;
    }

    static int getDrinkOffsetY() {
        return drinkOffsetY;
    }

    static void setFoodOffsets(int x, int y) {
        foodOffsetX = x;
        foodOffsetY = y;
    }

    static void setDrinkOffsets(int x, int y) {
        drinkOffsetX = x;
        drinkOffsetY = y;
    }

    static void moveFoodBy(int deltaX, int deltaY) {
        foodOffsetX += deltaX;
        foodOffsetY += deltaY;

        OffsetPair clamped = clampOffsetsToScreen(
                MinecraftClient.getInstance(),
                foodOffsetX,
                foodOffsetY,
                foodVerticalLayout,
                FOOD_SEGMENTS
        );
        foodOffsetX = clamped.x;
        foodOffsetY = clamped.y;
    }

    static void moveDrinkBy(int deltaX, int deltaY) {
        drinkOffsetX += deltaX;
        drinkOffsetY += deltaY;

        OffsetPair clamped = clampOffsetsToScreen(
                MinecraftClient.getInstance(),
                drinkOffsetX,
                drinkOffsetY,
                false,
                DRINK_SEGMENTS
        );
        drinkOffsetX = clamped.x;
        drinkOffsetY = clamped.y;
    }

    static void resetPositions() {
        foodOffsetX = 0;
        foodOffsetY = 0;
        drinkOffsetX = 0;
        drinkOffsetY = DEFAULT_DRINK_OFFSET_Y;
    }

    static EditHud.HudBounds getFoodEditorBounds() {
        return lastFoodEditorBounds;
    }

    static EditHud.HudBounds getDrinkEditorBounds() {
        return lastDrinkEditorBounds;
    }

    static void renderFoodEditorPreview(DrawContext context) {
        lastFoodEditorBounds = renderSegments(
                context,
                FOOD_SEGMENTS,
                FOOD_SEGMENTS,
                foodOffsetX,
                foodOffsetY,
                foodVerticalLayout,
                FOOD_FILLED_BACKGROUND
        );
    }

    static void renderDrinkEditorPreview(DrawContext context) {
        lastDrinkEditorBounds = renderSegments(
                context,
                DRINK_SEGMENTS,
                DRINK_SEGMENTS,
                drinkOffsetX,
                drinkOffsetY,
                false,
                DRINK_FILLED_BACKGROUND
        );
    }

    static void writeSettings(JsonObject root) {
        root.addProperty("foodStackEnabled", foodEnabled);
        root.addProperty("foodStackVertical", foodVerticalLayout);
        root.addProperty("foodStackOffsetX", foodOffsetX);
        root.addProperty("foodStackOffsetY", foodOffsetY);

        root.addProperty("drinkStackEnabled", drinkEnabled);
        root.addProperty("drinkStackOffsetX", drinkOffsetX);
        root.addProperty("drinkStackOffsetY", drinkOffsetY);
    }

    static void readSettings(JsonObject root) {
        if (root.has("foodStackEnabled")) {
            foodEnabled = root.get("foodStackEnabled").getAsBoolean();
        }
        if (root.has("foodStackVertical")) {
            foodVerticalLayout = root.get("foodStackVertical").getAsBoolean();
        }

        foodOffsetX = root.has("foodStackOffsetX")
                ? root.get("foodStackOffsetX").getAsInt()
                : 0;
        foodOffsetY = root.has("foodStackOffsetY")
                ? root.get("foodStackOffsetY").getAsInt()
                : 0;

        if (root.has("drinkStackEnabled")) {
            drinkEnabled = root.get("drinkStackEnabled").getAsBoolean();
        }
        drinkOffsetX = root.has("drinkStackOffsetX")
                ? root.get("drinkStackOffsetX").getAsInt()
                : 0;
        drinkOffsetY = root.has("drinkStackOffsetY")
                ? root.get("drinkStackOffsetY").getAsInt()
                : DEFAULT_DRINK_OFFSET_Y;
    }

    private static void saveSettings() {
        EditHud.saveSettings();
    }

    private static void registerCommands(
            CommandDispatcher<FabricClientCommandSource> dispatcher
    ) {
        registerFoodCommand(dispatcher);
        registerDrinkCommand(dispatcher);
    }

    private static void registerFoodCommand(
            CommandDispatcher<FabricClientCommandSource> dispatcher
    ) {
        dispatcher.register(
                ClientCommandManager.literal("fs")
                        .executes(context -> 1)
                        .then(ClientCommandManager.literal("reset")
                                .executes(context -> {
                                    resetFoodStack();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("toggle")
                                .executes(context -> {
                                    foodEnabled = !foodEnabled;
                                    saveSettings();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("horizontal")
                                .executes(context -> {
                                    foodVerticalLayout = false;
                                    clampFoodOffsetsToScreen();
                                    saveSettings();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("vertical")
                                .executes(context -> {
                                    foodVerticalLayout = true;
                                    clampFoodOffsetsToScreen();
                                    saveSettings();
                                    return 1;
                                }))
        );
    }

    private static void registerDrinkCommand(
            CommandDispatcher<FabricClientCommandSource> dispatcher
    ) {
        dispatcher.register(
                ClientCommandManager.literal("ds")
                        .executes(context -> 1)
                        .then(ClientCommandManager.literal("reset")
                                .executes(context -> {
                                    resetDrinkStack();
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("toggle")
                                .executes(context -> {
                                    drinkEnabled = !drinkEnabled;
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

        snapshotFoodStack = foodStack;
        snapshotDrinkStack = drinkStack;
        snapshotLastSignal = lastSignal;
        snapshotDedupeUntilMs = dedupeUntilMs;
        snapshotConsumptionSerial = consumptionSerial;

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
            resetAllStacks();
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

    private static void processPendingConsumptionTimer() {
        if (!pendingConsumptionSignal.isEmpty()
                && System.currentTimeMillis() > pendingConsumptionUntilMs) {
            clearPendingConsumption();
        }
    }

    private static void onIncomingMessage(String raw) {
        String clean = normalize(raw);
        handleRetryMessage(clean);
        handleConsumptionMessage(clean);
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
            if (consumptionSerial == snapshotConsumptionSerial) {
                restoreSnapshot();
            }
            lobbyUndoArmed = false;
        }
    }

    private static void handleConsumptionMessage(String clean) {
        long now = System.currentTimeMillis();
        ConsumptionType type = classifyConsumption(clean);
        boolean hasConsumedFoodHeader = clean.contains("먹은 음식:");

        if (hasConsumedFoodHeader) {
            if (type != null) {
                clearPendingConsumption();
                applyConsumption(type, clean, now);
            } else {
                pendingConsumptionSignal = clean;
                pendingConsumptionUntilMs = now + CONSUMPTION_CLASSIFY_WINDOW_MS;
            }
            return;
        }

        if (type != null
                && !pendingConsumptionSignal.isEmpty()
                && now <= pendingConsumptionUntilMs) {
            String signalSource = pendingConsumptionSignal;
            clearPendingConsumption();
            applyConsumption(type, signalSource, now);
        }
    }

    private static ConsumptionType classifyConsumption(String clean) {
        boolean thirst = clean.contains("갈증:");
        boolean hunger = clean.contains("허기:");

        if (thirst == hunger) {
            return null;
        }
        return thirst ? ConsumptionType.DRINK : ConsumptionType.FOOD;
    }

    private static void applyConsumption(
            ConsumptionType type,
            String signalSource,
            long now
    ) {
        String signal = type.name() + "|" + signalSource;
        if (signal.equals(lastSignal) && now < dedupeUntilMs) {
            return;
        }

        lastSignal = signal;
        dedupeUntilMs = now + DEDUPE_WINDOW_MS;
        consumptionSerial++;

        if (type == ConsumptionType.DRINK) {
            drinkStack = nextStack(drinkStack, DRINK_TRIGGER_EVERY, DRINK_SEGMENTS);
        } else {
            foodStack = nextStack(foodStack, FOOD_TRIGGER_EVERY, FOOD_SEGMENTS);
        }
    }

    private static int nextStack(int current, int triggerEvery, int visibleSegments) {
        int next = current + 1;
        return next >= triggerEvery
                ? 0
                : Math.min(next, visibleSegments);
    }

    private static void clearPendingConsumption() {
        pendingConsumptionSignal = "";
        pendingConsumptionUntilMs = 0L;
    }

    private static void tickWorldChangeReset(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            lastWorldKey = null;
            lobbyResetPending = false;
            lobbyUndoArmed = false;
            lastLobbyCommandMs = 0L;
            clearPendingConsumption();
            return;
        }

        RegistryKey<World> currentWorld = client.world.getRegistryKey();
        if (lastWorldKey == null || !lastWorldKey.equals(currentWorld)) {
            lastWorldKey = currentWorld;
            manualResetAll();
        }
    }

    private static void resetFoodStack() {
        foodStack = 0;
    }

    private static void resetDrinkStack() {
        drinkStack = 0;
    }

    private static void manualResetAll() {
        lobbyResetPending = false;
        lobbyUndoArmed = false;
        lastLobbyCommandMs = 0L;
        resetAllStacks();
    }

    private static void resetAllStacks() {
        foodStack = 0;
        drinkStack = 0;
        lastSignal = "";
        dedupeUntilMs = 0L;
        clearPendingConsumption();
    }

    private static void restoreSnapshot() {
        foodStack = snapshotFoodStack;
        drinkStack = snapshotDrinkStack;
        lastSignal = snapshotLastSignal;
        dedupeUntilMs = snapshotDedupeUntilMs;
        clearPendingConsumption();
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
                || client.currentScreen instanceof EditHud) {
            return;
        }

        if (foodEnabled) {
            renderSegments(
                    context,
                    FOOD_SEGMENTS,
                    foodStack,
                    foodOffsetX,
                    foodOffsetY,
                    foodVerticalLayout,
                    FOOD_FILLED_BACKGROUND
            );
        }

        if (drinkEnabled) {
            renderSegments(
                    context,
                    DRINK_SEGMENTS,
                    drinkStack,
                    drinkOffsetX,
                    drinkOffsetY,
                    false,
                    DRINK_FILLED_BACKGROUND
            );
        }
    }

    private static EditHud.HudBounds renderSegments(
            DrawContext context,
            int segmentCount,
            int filledSegments,
            int horizontalOffset,
            int verticalOffset,
            boolean vertical,
            int filledBackground
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return new EditHud.HudBounds(0, 0, 1, 1);
        }

        LayoutBox box = computeLayout(
                client,
                horizontalOffset,
                verticalOffset,
                vertical,
                segmentCount
        );
        box = clampLayoutToScreen(client, box);

        for (int index = 0; index < segmentCount; index++) {
            int x;
            int y;

            if (vertical) {
                x = box.startX;
                y = box.startY + (segmentCount - 1 - index) * (SEGMENT_HEIGHT + SEGMENT_GAP);
            } else {
                x = box.startX + index * (SEGMENT_WIDTH + SEGMENT_GAP);
                y = box.startY;
            }

            int x2 = x + SEGMENT_WIDTH;
            int y2 = y + SEGMENT_HEIGHT;
            boolean filled = index < filledSegments;

            context.fill(x - 1, y - 1, x2 + 1, y2 + 1, EMPTY_EDGE);
            context.fill(x, y, x2, y2, filled ? filledBackground : EMPTY_BACKGROUND);

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
            boolean vertical,
            int segmentCount
    ) {
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int centerX = screenWidth / 2 + horizontalOffset;
        int centerY = screenHeight / 2 + verticalOffset + DEFAULT_Y_FROM_CROSSHAIR;

        if (vertical) {
            int totalHeight = segmentCount * SEGMENT_HEIGHT
                    + (segmentCount - 1) * SEGMENT_GAP;
            return new LayoutBox(
                    centerX - SEGMENT_WIDTH / 2,
                    centerY,
                    SEGMENT_WIDTH,
                    totalHeight
            );
        }

        int totalWidth = segmentCount * SEGMENT_WIDTH
                + (segmentCount - 1) * SEGMENT_GAP;
        return new LayoutBox(
                centerX - totalWidth / 2,
                centerY,
                totalWidth,
                SEGMENT_HEIGHT
        );
    }

    private static LayoutBox clampLayoutToScreen(
            MinecraftClient client,
            LayoutBox box
    ) {
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int x = box.startX;
        int y = box.startY;

        if (x < SCREEN_MARGIN) {
            x = SCREEN_MARGIN;
        }
        if (x + box.totalWidth > screenWidth - SCREEN_MARGIN) {
            x = screenWidth - SCREEN_MARGIN - box.totalWidth;
        }
        if (y < SCREEN_MARGIN) {
            y = SCREEN_MARGIN;
        }
        if (y + box.totalHeight > screenHeight - SCREEN_MARGIN) {
            y = screenHeight - SCREEN_MARGIN - box.totalHeight;
        }

        return new LayoutBox(
                x,
                y,
                box.totalWidth,
                box.totalHeight
        );
    }

    private static void clampFoodOffsetsToScreen() {
        OffsetPair clamped = clampOffsetsToScreen(
                MinecraftClient.getInstance(),
                foodOffsetX,
                foodOffsetY,
                foodVerticalLayout,
                FOOD_SEGMENTS
        );
        foodOffsetX = clamped.x;
        foodOffsetY = clamped.y;
    }

    private static OffsetPair clampOffsetsToScreen(
            MinecraftClient client,
            int horizontalOffset,
            int verticalOffset,
            boolean vertical,
            int segmentCount
    ) {
        if (client == null) {
            return new OffsetPair(horizontalOffset, verticalOffset);
        }

        LayoutBox box = computeLayout(
                client,
                horizontalOffset,
                verticalOffset,
                vertical,
                segmentCount
        );
        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int x = horizontalOffset;
        int y = verticalOffset;

        if (box.startX < SCREEN_MARGIN) {
            x += SCREEN_MARGIN - box.startX;
        }
        if (box.startX + box.totalWidth > screenWidth - SCREEN_MARGIN) {
            x -= box.startX + box.totalWidth - (screenWidth - SCREEN_MARGIN);
        }
        if (box.startY < SCREEN_MARGIN) {
            y += SCREEN_MARGIN - box.startY;
        }
        if (box.startY + box.totalHeight > screenHeight - SCREEN_MARGIN) {
            y -= box.startY + box.totalHeight - (screenHeight - SCREEN_MARGIN);
        }

        return new OffsetPair(x, y);
    }

    private record OffsetPair(int x, int y) {
    }

    private record LayoutBox(
            int startX,
            int startY,
            int totalWidth,
            int totalHeight
    ) {
    }
}
