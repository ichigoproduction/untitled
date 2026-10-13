package untitled.untitled.client;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
    private static final int DEFAULT_FOOD_SEGMENTS = 3;
    private static final int MAX_FOOD_SEGMENTS = 4;
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
    private static final int CLASSIC_HIGHLIGHT = 0x99FFFFFF;
    private static final int PIXEL_HIGHLIGHT = 0x55FFFFFF;
    private static final int CLASSIC_EDGE = 0x66000000;
    private static final int PIXEL_EDGE = 0xAA455061;
    private static final int PIXEL_BLACK_EDGE = 0xCCFFFFFF;
    private static final int PIXEL_EMPTY_EDGE = 0x8867778B;

    private enum HudColor {
        PINK(0xFFFFB6D9), BLUE(0xFFADD8FF), GREEN(0xFFB8E9BD),
        YELLOW(0xFFFFE59C), PURPLE(0xFFD9B9FF), ORANGE(0xFFFFC999),
        RED(0xFFFFADAD), MINT(0xFFADEEDC), PEACH(0xFFFFD0B8),
        CYAN(0xFFA9EAF2), LAVENDER(0xFFE1D2FF),
        WHITE(0xFFFFFFFF), BLACK(0xFF000000), GRAY(0xFFB8BEC7);

        private final int argb;
        HudColor(int argb) { this.argb = argb; }
        int argb() { return argb; }
    }

    // Original outer-outline HUD and recent inner-outline pixel variant.
    private enum HudShape {
        CLASSIC, PIXEL
    }

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
    private static int foodSegments = DEFAULT_FOOD_SEGMENTS;
    private static HudColor foodColor = HudColor.PINK;
    private static HudColor drinkColor = HudColor.BLUE;
    private static HudShape foodShape = HudShape.CLASSIC;
    private static HudShape drinkShape = HudShape.CLASSIC;

    private static int foodOffsetX = 0;
    private static int foodOffsetY = 0;
    private static int drinkOffsetX = 0;
    private static int drinkOffsetY = DEFAULT_DRINK_OFFSET_Y;

    private static int foodStack = 0;
    private static int drinkStack = 0;

    private static RegistryKey<World> lastWorldKey = null;
    private static long consumptionSerial = 0L;

    private static long lastLobbyCommandMs = 0L;
    private static boolean lobbyResetPending = false;
    private static long lobbyResetDueMs = 0L;
    private static boolean lobbyUndoArmed = false;
    private static long lobbyUndoDeadlineMs = 0L;

    private static int snapshotFoodStack = 0;
    private static int snapshotDrinkStack = 0;
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
                foodSegments,
                foodShape
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
                DRINK_SEGMENTS,
                drinkShape
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
                foodSegments,
                foodSegments,
                foodOffsetX,
                foodOffsetY,
                foodVerticalLayout,
                foodColor,
                foodShape
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
                drinkColor,
                drinkShape
        );
    }

    static void writeSettings(JsonObject root) {
        root.addProperty("foodStackEnabled", foodEnabled);
        root.addProperty("foodStackVertical", foodVerticalLayout);
        root.addProperty("foodStackSegments", foodSegments);
        root.addProperty("foodStackColor", foodColor.name().toLowerCase(Locale.ROOT));
        root.addProperty("foodStackShape", foodShape.name().toLowerCase(Locale.ROOT));
        root.addProperty("foodStackOffsetX", foodOffsetX);
        root.addProperty("foodStackOffsetY", foodOffsetY);

        root.addProperty("drinkStackEnabled", drinkEnabled);
        root.addProperty("drinkStackColor", drinkColor.name().toLowerCase(Locale.ROOT));
        root.addProperty("drinkStackShape", drinkShape.name().toLowerCase(Locale.ROOT));
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
        if (root.has("foodStackSegments")) {
            try {
                int saved = root.get("foodStackSegments").getAsInt();
                if (saved == DEFAULT_FOOD_SEGMENTS || saved == MAX_FOOD_SEGMENTS) {
                    foodSegments = saved;
                }
            } catch (RuntimeException ignored) {
                // Invalid saved values fall back to the current/default mode.
            }
        }
        if (root.has("foodStackColor")) {
            try {
                foodColor = HudColor.valueOf(root.get("foodStackColor").getAsString().toUpperCase(Locale.ROOT));
            } catch (RuntimeException ignored) {
                // Invalid presets keep the default color.
            }
        }
        if (root.has("foodStackShape")) {
            try {
                foodShape = HudShape.valueOf(root.get("foodStackShape").getAsString().toUpperCase(Locale.ROOT));
            } catch (RuntimeException ignored) {
                // Invalid presets keep the default shape.
            }
        }
        foodStack = Math.min(foodStack, foodSegments);
        snapshotFoodStack = Math.min(snapshotFoodStack, foodSegments);

        foodOffsetX = root.has("foodStackOffsetX")
                ? root.get("foodStackOffsetX").getAsInt()
                : 0;
        foodOffsetY = root.has("foodStackOffsetY")
                ? root.get("foodStackOffsetY").getAsInt()
                : 0;

        if (root.has("drinkStackEnabled")) {
            drinkEnabled = root.get("drinkStackEnabled").getAsBoolean();
        }
        if (root.has("drinkStackColor")) {
            try {
                drinkColor = HudColor.valueOf(root.get("drinkStackColor").getAsString().toUpperCase(Locale.ROOT));
            } catch (RuntimeException ignored) {
                // Invalid presets keep the default color.
            }
        }
        if (root.has("drinkStackShape")) {
            try {
                drinkShape = HudShape.valueOf(root.get("drinkStackShape").getAsString().toUpperCase(Locale.ROOT));
            } catch (RuntimeException ignored) {
                // Invalid presets keep the default shape.
            }
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

    private static int setFoodSegments(int segments) {
        if (segments != DEFAULT_FOOD_SEGMENTS && segments != MAX_FOOD_SEGMENTS) {
            return 0;
        }
        foodSegments = segments;
        foodStack = Math.min(foodStack, foodSegments);
        snapshotFoodStack = Math.min(snapshotFoodStack, foodSegments);
        clampFoodOffsetsToScreen();
        saveSettings();
        return 1;
    }

    private static int setHudColor(boolean food, HudColor color) {
        if (food) foodColor = color;
        else drinkColor = color;
        saveSettings();
        return 1;
    }

    private static int setHudShape(boolean food, HudShape shape) {
        if (food) {
            foodShape = shape;
            clampFoodOffsetsToScreen();
        } else {
            drinkShape = shape;
            clampDrinkOffsetsToScreen();
        }
        saveSettings();
        return 1;
    }

    private static void addAppearanceCommands(
            LiteralArgumentBuilder<FabricClientCommandSource> root,
            boolean food
    ) {
        var colors = ClientCommandManager.literal("color");
        for (HudColor color : HudColor.values()) {
            colors.then(ClientCommandManager.literal(color.name().toLowerCase(Locale.ROOT))
                    .executes(ctx -> setHudColor(food, color)));
        }
        root.then(colors);

        var shapes = ClientCommandManager.literal("shape");
        for (HudShape shape : HudShape.values()) {
            shapes.then(ClientCommandManager.literal(shape.name().toLowerCase(Locale.ROOT))
                    .executes(ctx -> setHudShape(food, shape)));
        }
        root.then(shapes);
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
        var root = ClientCommandManager.literal("fs")
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
                        .then(ClientCommandManager.literal("stack")
                                .then(ClientCommandManager.literal("3")
                                        .executes(context -> setFoodSegments(3)))
                                .then(ClientCommandManager.literal("4")
                                        .executes(context -> setFoodSegments(4))));
        addAppearanceCommands(root, true);
        dispatcher.register(root);
    }

    private static void registerDrinkCommand(
            CommandDispatcher<FabricClientCommandSource> dispatcher
    ) {
        var root = ClientCommandManager.literal("ds")
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
                                }));
        addAppearanceCommands(root, false);
        dispatcher.register(root);
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

    private static void onIncomingMessage(String raw) {
        String clean = normalize(raw);
        handleRetryMessage(clean);
        if (clean.contains("음식 버프 적용!")) {
            // Apply every actual server buff notification without an
            // artificial time-based ignore window.
            resetFoodStack();
            return;
        }
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
        // The server may send "먹은 음식:" and the hunger/thirst result as
        // separate messages. Count the explicit hunger/thirst event itself;
        // no pending messages, pairing timer, or timing-based suppression.
        ConsumptionType type = classifyConsumption(clean);
        if (type != null) {
            applyConsumption(type);
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

    private static void applyConsumption(ConsumptionType type) {
        // Count every completed consumption event; do not discard identical
        // events based on their text or a short time window.
        consumptionSerial++;

        if (type == ConsumptionType.DRINK) {
            drinkStack = nextStack(drinkStack, DRINK_TRIGGER_EVERY, DRINK_SEGMENTS);
        } else {
            foodStack = Math.min(foodStack + 1, foodSegments);
        }
    }

    private static int nextStack(int current, int triggerEvery, int visibleSegments) {
        int next = current + 1;
        return next >= triggerEvery
                ? 0
                : Math.min(next, visibleSegments);
    }

    private static void tickWorldChangeReset(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) {
            lastWorldKey = null;
            lobbyResetPending = false;
            lobbyUndoArmed = false;
            lastLobbyCommandMs = 0L;
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
    }

    private static void restoreSnapshot() {
        foodStack = Math.min(snapshotFoodStack, foodSegments);
        drinkStack = snapshotDrinkStack;
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
                    foodSegments,
                    foodStack,
                    foodOffsetX,
                    foodOffsetY,
                    foodVerticalLayout,
                    foodColor,
                    foodShape
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
                    drinkColor,
                    drinkShape
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
            HudColor color,
            HudShape shape
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return new EditHud.HudBounds(0, 0, 1, 1);

        LayoutBox box = computeLayout(client, horizontalOffset, verticalOffset,
                vertical, segmentCount, shape);
        box = clampLayoutToScreen(client, box);

        for (int index = 0; index < segmentCount; index++) {
            int x = vertical ? box.startX
                    : box.startX + index * (SEGMENT_WIDTH + SEGMENT_GAP);
            int y = vertical
                    ? box.startY + (segmentCount - 1 - index) * (SEGMENT_HEIGHT + SEGMENT_GAP)
                    : box.startY;
            int x2 = x + SEGMENT_WIDTH;
            int y2 = y + SEGMENT_HEIGHT;
            boolean filled = index < filledSegments;

            if (shape == HudShape.CLASSIC) {
                // Original rendering order, 1px external outline and top stripe.
                context.fill(x - 1, y - 1, x2 + 1, y2 + 1, CLASSIC_EDGE);
                context.fill(x, y, x2, y2, filled ? color.argb() : EMPTY_BACKGROUND);
                if (filled) context.fill(x, y, x2, y + 1, CLASSIC_HIGHLIGHT);
            } else {
                // Recent 'pixel' appearance: internal outline and thin inner fill.
                int edge = !filled ? PIXEL_EMPTY_EDGE
                        : (color == HudColor.BLACK ? PIXEL_BLACK_EDGE : PIXEL_EDGE);
                context.fill(x, y, x2, y2, edge);
                context.fill(x + 1, y + 1, x2 - 1, y2 - 1,
                        filled ? color.argb() : EMPTY_BACKGROUND);
                if (filled) context.fill(x + 2, y + 1, x2 - 2, y + 2, PIXEL_HIGHLIGHT);
            }
        }
        return new EditHud.HudBounds(box.startX, box.startY, box.totalWidth, box.totalHeight);
    }

    private static LayoutBox computeLayout(
            MinecraftClient client,
            int horizontalOffset,
            int verticalOffset,
            boolean vertical,
            int segmentCount,
            HudShape shape
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
                foodSegments,
                foodShape
        );
        foodOffsetX = clamped.x;
        foodOffsetY = clamped.y;
    }

    private static void clampDrinkOffsetsToScreen() {
        OffsetPair clamped = clampOffsetsToScreen(
                MinecraftClient.getInstance(), drinkOffsetX, drinkOffsetY,
                false, DRINK_SEGMENTS, drinkShape
        );
        drinkOffsetX = clamped.x;
        drinkOffsetY = clamped.y;
    }

    private static OffsetPair clampOffsetsToScreen(
            MinecraftClient client,
            int horizontalOffset,
            int verticalOffset,
            boolean vertical,
            int segmentCount,
            HudShape shape
    ) {
        if (client == null) {
            return new OffsetPair(horizontalOffset, verticalOffset);
        }

        LayoutBox box = computeLayout(
                client,
                horizontalOffset,
                verticalOffset,
                vertical,
                segmentCount,
                shape
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
