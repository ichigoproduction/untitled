package partyhud.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class PartyHud {
    public enum ViewMode {
        DETAIL,
        COMPACT
    }

    private static final int NAME_IDLE = 0xFFEFEFEF;
    private static final int NAME_TEAM = 0xFFA8F0C0;
    private static final int NAME_ENEMY = 0xFFFF9A9A;
    private static final int DIST_COLOR = 0xFFD7E6FF;
    private static final int DY_COLOR = 0xFFBDC3C7;
    private static final int ARMOR_COLOR = 0xFFB7C7D9;
    private static final int MUTED = 0xFF8A8A8A;
    private static final int SNEAK_COLOR = 0xFF7FE4FF;
    private static final int PANEL_COLOR = 0x880A0D12;
    private static final int BORDER_COLOR = 0x55FFFFFF;
    private static final int SEPARATOR_COLOR = 0x35FFFFFF;

    private static final int HP_RED = 0xFFFF5A5A;
    private static final int HP_YELLOW = 0xFFFFD56A;
    private static final int HP_LIGHT_GREEN = 0xFFB9F6A5;
    private static final int HP_DARK_GREEN = 0xFF4FD37E;

    private static final int ROW_GAP = 4;
    private static final int COLUMN_GAP = 8;
    private static final int PAD_X = 7;
    private static final int PAD_Y = 6;
    private static final int ICON_SIZE = 10;
    private static final int ICON_GAP = 5;
    private static final int SOFT_MARGIN = 2;

    private static final long LAST_SEEN_KEEP_MS = 15_000L;
    private static final long CLEANUP_INTERVAL_MS = 5_000L;

    private static final Map<UUID, LastSeen> LAST_SEEN = new HashMap<>();
    private static final Map<UUID, Identifier> SKIN_CACHE = new HashMap<>();

    private static final List<Row> EDITOR_ROWS = List.of(
            Row.preview(
                    UUID.nameUUIDFromBytes("party-editor-one".getBytes(StandardCharsets.UTF_8)),
                    "PartyOne",
                    Targeting.Affinity.TEAM,
                    18.0,
                    2.0,
                    18.5,
                    -35.0,
                    false,
                    12
            ),
            Row.preview(
                    UUID.nameUUIDFromBytes("party-editor-two".getBytes(StandardCharsets.UTF_8)),
                    "PartyTwo",
                    Targeting.Affinity.ENEMY,
                    31.0,
                    -4.0,
                    7.5,
                    48.0,
                    true,
                    8
            )
    );

    private static boolean initialized = false;
    private static boolean loadingSettings = false;
    private static ViewMode viewMode = ViewMode.COMPACT;
    private static int offsetX = 0;
    private static int offsetY = 0;
    private static long lastCleanupMs = 0L;
    private static PartyHudEditor.HudBounds lastEditorBounds =
            new PartyHudEditor.HudBounds(10, 30, 1, 1);

    private PartyHud() {
    }

    public static void init() {
        if (initialized) return;
        initialized = true;

        HudRenderCallback.EVENT.register(PartyHud::render);
        registerCommands();
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
    }

    static void moveBy(int dx, int dy) {
        offsetX += dx;
        offsetY += dy;
    }

    static void resetPosition() {
        offsetX = 0;
        offsetY = 0;
    }

    static PartyHudEditor.HudBounds getEditorBounds() {
        return lastEditorBounds;
    }

    static void renderEditorPreview(DrawContext context) {
        RenderLayout layout = renderRows(context, EDITOR_ROWS, true);
        if (layout != null) {
            lastEditorBounds = new PartyHudEditor.HudBounds(
                    layout.x(), layout.y(), layout.width(), layout.height()
            );
        }
    }

    static void writeSettings(JsonObject root) {
        root.addProperty("offsetX", offsetX);
        root.addProperty("offsetY", offsetY);
        root.addProperty("viewMode", viewMode.name());
        root.addProperty("sortMode", Targeting.getSortMode().name());

        JsonArray targets = new JsonArray();
        for (Targeting.TargetInfo target : Targeting.all()) {
            JsonObject item = new JsonObject();
            item.addProperty("uuid", target.uuid.toString());
            item.addProperty("name", target.name);
            item.addProperty("affinity", target.affinity.name());
            targets.add(item);
        }
        root.add("targets", targets);
    }

    static void readSettings(JsonObject root) {
        loadingSettings = true;
        try {
            offsetX = root.has("offsetX") ? root.get("offsetX").getAsInt() : 0;
            offsetY = root.has("offsetY") ? root.get("offsetY").getAsInt() : 0;

            if (root.has("viewMode")) {
                try {
                    viewMode = ViewMode.valueOf(root.get("viewMode").getAsString());
                } catch (IllegalArgumentException ignored) {
                    viewMode = ViewMode.COMPACT;
                }
            }

            if (root.has("sortMode")) {
                try {
                    Targeting.sortMode = Targeting.SortMode.valueOf(
                            root.get("sortMode").getAsString()
                    );
                } catch (IllegalArgumentException ignored) {
                    Targeting.sortMode = Targeting.SortMode.ORDER;
                }
            }

            Targeting.TARGETS.clear();
            if (root.has("targets") && root.get("targets").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("targets")) {
                    if (!element.isJsonObject()) continue;

                    JsonObject item = element.getAsJsonObject();
                    if (!item.has("uuid") || !item.has("name")) continue;

                    try {
                        UUID uuid = UUID.fromString(item.get("uuid").getAsString());
                        String name = item.get("name").getAsString();
                        Targeting.Affinity affinity = Targeting.Affinity.NEUTRAL;

                        if (item.has("affinity")) {
                            try {
                                affinity = Targeting.Affinity.valueOf(
                                        item.get("affinity").getAsString()
                                );
                            } catch (IllegalArgumentException ignored) {
                            }
                        }

                        if (!name.isBlank()) {
                            Targeting.TARGETS.put(
                                    uuid,
                                    new Targeting.TargetInfo(uuid, name, affinity)
                            );
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } finally {
            loadingSettings = false;
        }
    }

    private static void saveSettings() {
        if (!loadingSettings) {
            PartyHudEditor.saveSettings();
        }
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("party")
                        .then(argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> suggestPlayersForAdd(builder))
                                .then(argument("role", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            builder.suggest("team");
                                            builder.suggest("enemy");
                                            builder.suggest("neutral");
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            String name = StringArgumentType.getString(context, "player");
                                            String role = StringArgumentType.getString(context, "role");
                                            return resultCode(Targeting.addOrEnsureByName(
                                                    name,
                                                    Targeting.Affinity.fromCommand(role)
                                            ));
                                        }))
                                .executes(context -> resultCode(Targeting.addOrEnsureByName(
                                        StringArgumentType.getString(context, "player")
                                ))))
                        .then(literal("remove")
                                .then(argument("player", StringArgumentType.word())
                                        .suggests((context, builder) -> suggestPlayersForRemove(builder))
                                        .executes(context -> {
                                            boolean removed = Targeting.removeByName(
                                                    StringArgumentType.getString(context, "player")
                                            );
                                            beep(removed ? 1.05F : 0.9F);
                                            return removed ? 1 : 0;
                                        })))
                        .then(literal("clear").executes(context -> {
                            Targeting.clearAll();
                            beep(1.0F);
                            return 1;
                        }))
                        .then(literal("sort")
                                .then(literal("order").executes(context -> {
                                    Targeting.setSortMode(Targeting.SortMode.ORDER);
                                    return 1;
                                }))
                                .then(literal("distance").executes(context -> {
                                    Targeting.setSortMode(Targeting.SortMode.DISTANCE);
                                    return 1;
                                }))
                                .then(literal("health").executes(context -> {
                                    Targeting.setSortMode(Targeting.SortMode.HEALTH);
                                    return 1;
                                })))
                        .then(literal("mode")
                                .then(literal("compact").executes(context -> {
                                    viewMode = ViewMode.COMPACT;
                                    saveSettings();
                                    beep(1.1F);
                                    return 1;
                                }))
                                .then(literal("detail").executes(context -> {
                                    viewMode = ViewMode.DETAIL;
                                    saveSettings();
                                    beep(1.0F);
                                    return 1;
                                })))
                )
        );
    }

    private static int resultCode(Targeting.AddResult result) {
        return switch (result) {
            case ADDED -> {
                beep(1.1F);
                yield 1;
            }
            case EXISTS -> {
                beep(1.0F);
                yield 1;
            }
            case NOT_FOUND -> {
                beep(0.9F);
                yield 0;
            }
        };
    }

    private static void beep(float pitch) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.getSoundManager().play(
                PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 1.0F)
        );
    }

    private static CompletableFuture<Suggestions> suggestPlayersForAdd(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining() == null ? "" : builder.getRemaining().trim();
        String lower = remaining.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> names = new LinkedHashSet<>();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getNetworkHandler() != null) {
            for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                if (entry.getProfile() == null) continue;
                String name = entry.getProfile().getName();
                if (name != null && !name.isEmpty()) names.add(name);
            }
        }

        for (Targeting.TargetInfo target : Targeting.all()) {
            names.add(target.name);
        }

        for (String name : names) {
            if (lower.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestPlayersForRemove(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining() == null ? "" : builder.getRemaining().trim();
        String lower = remaining.toLowerCase(Locale.ROOT);

        for (Targeting.TargetInfo target : Targeting.all()) {
            if (lower.isEmpty() || target.name.toLowerCase(Locale.ROOT).startsWith(lower)) {
                builder.suggest(target.name);
            }
        }
        return builder.buildFuture();
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) return;
        if (client.currentScreen instanceof PartyHudEditor) return;
        if (client.options.hudHidden || Targeting.all().isEmpty()) return;

        long now = System.currentTimeMillis();
        if (now - lastCleanupMs >= CLEANUP_INTERVAL_MS) {
            cleanupOldEntries(now);
            lastCleanupMs = now;
        }

        float tickDelta = tickCounter.getTickDelta(false);
        List<Row> rows = buildRows(client, tickDelta, now);
        sortRows(rows);
        renderRows(context, rows, false);
    }

    private static List<Row> buildRows(MinecraftClient client, float tickDelta, long now) {
        Vec3d forward = client.player.getRotationVec(tickDelta);
        List<Row> rows = new ArrayList<>();

        for (Targeting.TargetInfo target : Targeting.all()) {
            PlayerEntity player = client.world.getPlayerByUuid(target.uuid);
            if (player != null) {
                double dx = player.getX() - client.player.getX();
                double dy = player.getY() - client.player.getY();
                double dz = player.getZ() - client.player.getZ();
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double angle = directionAngle(forward, dx, dz);
                double health = player.getHealth() + player.getAbsorptionAmount();
                int armor = player.getArmor();
                boolean sneaking = player.isSneaking();

                Identifier skin = resolveSkin(client, target.uuid);
                LAST_SEEN.put(target.uuid, new LastSeen(
                        player.getX(), player.getY(), player.getZ(), now, skin, sneaking
                ));

                rows.add(Row.live(
                        target.uuid,
                        target.name,
                        target.affinity,
                        distance,
                        dy,
                        health,
                        angle,
                        sneaking,
                        armor
                ));
                continue;
            }

            LastSeen memory = LAST_SEEN.get(target.uuid);
            if (memory == null) {
                rows.add(Row.unloaded(
                        target.uuid,
                        target.name,
                        target.affinity,
                        Double.NaN,
                        Double.NaN,
                        Double.NaN,
                        0.0,
                        true,
                        false,
                        -1
                ));
                continue;
            }

            long age = now - memory.timeMs;
            boolean stale = age > LAST_SEEN_KEEP_MS;
            double distance = Double.NaN;
            double dy = Double.NaN;
            double angle = 0.0;

            if (!stale) {
                double dx = memory.x - client.player.getX();
                dy = memory.y - client.player.getY();
                double dz = memory.z - client.player.getZ();
                distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                angle = directionAngle(forward, dx, dz);
            }

            rows.add(Row.unloaded(
                    target.uuid,
                    target.name,
                    target.affinity,
                    distance,
                    dy,
                    Double.NaN,
                    angle,
                    stale,
                    memory.sneaking,
                    -1
            ));
        }

        return rows;
    }

    private static double directionAngle(Vec3d forward, double dx, double dz) {
        double dot = forward.x * dx + forward.z * dz;
        double cross = forward.x * dz - forward.z * dx;
        return Math.toDegrees(Math.atan2(cross, dot));
    }

    private static void sortRows(List<Row> rows) {
        Comparator<Row> comparator = switch (Targeting.getSortMode()) {
            case ORDER -> null;
            case DISTANCE -> Comparator.comparingDouble(row ->
                    Double.isNaN(row.distance) ? Double.POSITIVE_INFINITY : row.distance
            );
            case HEALTH -> Comparator.comparingDouble(row ->
                    Double.isNaN(row.health) ? Double.POSITIVE_INFINITY : row.health
            );
        };

        if (comparator != null) rows.sort(comparator);
    }

    private static RenderLayout renderRows(DrawContext context, List<Row> rows, boolean editor) {
        if (rows.isEmpty()) return null;

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;
        int screenHeight = client.getWindow().getScaledHeight();

        int lineHeight = Math.max(font.fontHeight, ICON_SIZE);
        int rowStep = lineHeight + ROW_GAP;
        int baseX = 10 + offsetX;
        int baseY = (editor ? 34 : 10) + offsetY;

        int availableHeight = Math.max(
                rowStep,
                screenHeight - Math.max(0, baseY) - SOFT_MARGIN - PAD_Y * 2
        );
        int rowsPerColumn = Math.max(1, availableHeight / rowStep);

        int currentX = baseX;
        int maxBottom = baseY;
        int totalRight = baseX;

        for (int start = 0; start < rows.size(); start += rowsPerColumn) {
            int end = Math.min(rows.size(), start + rowsPerColumn);
            List<Row> columnRows = rows.subList(start, end);
            int columnWidth = calculateColumnWidth(font, columnRows);
            int columnHeight = PAD_Y * 2 + columnRows.size() * rowStep - ROW_GAP;

            drawPanel(context, currentX, baseY, columnWidth, columnHeight);
            drawColumnRows(
                    context,
                    font,
                    columnRows,
                    currentX,
                    baseY,
                    columnWidth,
                    lineHeight,
                    rowStep
            );

            totalRight = currentX + columnWidth;
            maxBottom = Math.max(maxBottom, baseY + columnHeight);
            currentX += columnWidth + COLUMN_GAP;
        }

        int width = Math.max(1, totalRight - baseX);
        int height = Math.max(1, maxBottom - baseY);
        return new RenderLayout(baseX, baseY, width, height);
    }

    private static int calculateColumnWidth(TextRenderer font, List<Row> rows) {
        int maximum = 0;
        for (Row row : rows) {
            String line = buildLine(row);
            maximum = Math.max(maximum, font.getWidth(line));
        }
        return PAD_X * 2 + ICON_SIZE + ICON_GAP + maximum;
    }

    private static void drawPanel(DrawContext context, int x, int y, int width, int height) {
        context.fill(x, y, x + width, y + height, PANEL_COLOR);
        context.fill(x, y, x + width, y + 1, BORDER_COLOR);
        context.fill(x, y + height - 1, x + width, y + height, BORDER_COLOR);
        context.fill(x, y, x + 1, y + height, BORDER_COLOR);
        context.fill(x + width - 1, y, x + width, y + height, BORDER_COLOR);
    }

    private static void drawColumnRows(
            DrawContext context,
            TextRenderer font,
            List<Row> rows,
            int panelX,
            int panelY,
            int panelWidth,
            int lineHeight,
            int rowStep
    ) {
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            int rowY = panelY + PAD_Y + index * rowStep;
            int iconY = rowY + Math.max(0, (lineHeight - ICON_SIZE) / 2);
            int textX = panelX + PAD_X + ICON_SIZE + ICON_GAP;
            int textY = rowY + Math.max(0, (lineHeight - font.fontHeight) / 2);

            drawHead(
                    context,
                    getSkinId(row.uuid),
                    panelX + PAD_X,
                    iconY,
                    ICON_SIZE,
                    ICON_SIZE
            );
            drawRowText(context, font, row, textX, textY);

            if (index < rows.size() - 1) {
                int separatorY = rowY + lineHeight + ROW_GAP / 2;
                context.fill(
                        panelX + PAD_X,
                        separatorY,
                        panelX + panelWidth - PAD_X,
                        separatorY + 1,
                        SEPARATOR_COLOR
                );
            }
        }
    }

    private static String buildLine(Row row) {
        if (viewMode == ViewMode.COMPACT) {
            return row.name + (row.sneaking ? " [S]" : "")
                    + "  " + row.distanceCompact()
                    + "  " + row.healthText()
                    + "  " + arrowFor(row);
        }

        return row.name + (row.sneaking ? " [S]" : "")
                + "  D:" + row.distanceDetail()
                + "  Y:" + row.deltaYText()
                + "  HP:" + row.healthText()
                + "  A:" + row.armorText()
                + "  " + arrowFor(row);
    }

    private static void drawRowText(
            DrawContext context,
            TextRenderer font,
            Row row,
            int x,
            int y
    ) {
        int nameColor = switch (row.affinity) {
            case TEAM -> NAME_TEAM;
            case ENEMY -> NAME_ENEMY;
            case NEUTRAL -> NAME_IDLE;
        };
        if (row.unloaded) nameColor = MUTED;

        int cursor = x;
        cursor = drawText(context, font, row.name, cursor, y, nameColor);

        if (row.sneaking) {
            cursor = drawText(
                    context,
                    font,
                    " [S]",
                    cursor,
                    y,
                    row.unloaded ? MUTED : SNEAK_COLOR
            );
        }

        if (viewMode == ViewMode.COMPACT) {
            cursor = drawText(
                    context,
                    font,
                    "  " + row.distanceCompact(),
                    cursor,
                    y,
                    row.unloaded ? MUTED : DIST_COLOR
            );
            cursor = drawText(
                    context,
                    font,
                    "  " + row.healthText(),
                    cursor,
                    y,
                    row.unloaded ? MUTED : healthColor(row.health)
            );
            drawText(
                    context,
                    font,
                    "  " + arrowFor(row),
                    cursor,
                    y,
                    row.stale ? MUTED : DIST_COLOR
            );
            return;
        }

        cursor = drawText(
                context,
                font,
                "  D:" + row.distanceDetail(),
                cursor,
                y,
                row.unloaded ? MUTED : DIST_COLOR
        );
        cursor = drawText(
                context,
                font,
                "  Y:" + row.deltaYText(),
                cursor,
                y,
                row.unloaded ? MUTED : DY_COLOR
        );
        cursor = drawText(
                context,
                font,
                "  HP:" + row.healthText(),
                cursor,
                y,
                row.unloaded ? MUTED : healthColor(row.health)
        );
        cursor = drawText(
                context,
                font,
                "  A:" + row.armorText(),
                cursor,
                y,
                row.unloaded ? MUTED : ARMOR_COLOR
        );
        drawText(
                context,
                font,
                "  " + arrowFor(row),
                cursor,
                y,
                row.stale ? MUTED : DIST_COLOR
        );
    }

    private static int drawText(
            DrawContext context,
            TextRenderer font,
            String value,
            int x,
            int y,
            int color
    ) {
        context.drawText(font, value, x, y, color, true);
        return x + font.getWidth(value);
    }

    private static String arrowFor(Row row) {
        if (row.stale || Double.isNaN(row.distance)) return "—";

        double normalized = ((row.angleDegrees % 360.0) + 360.0) % 360.0;
        int sector = (int) Math.round(normalized / 45.0) & 7;
        return switch (sector) {
            case 0 -> "↑";
            case 1 -> "↖";
            case 2 -> "←";
            case 3 -> "↙";
            case 4 -> "↓";
            case 5 -> "↘";
            case 6 -> "→";
            default -> "↗";
        };
    }

    private static int healthColor(double health) {
        if (Double.isNaN(health)) return MUTED;
        if (health <= 6.0) return HP_RED;
        if (health < 13.0) {
            return lerpColor(
                    HP_RED,
                    HP_YELLOW,
                    (float) ((health - 6.0) / 7.0)
            );
        }
        if (health < 21.0) {
            return lerpColor(
                    HP_YELLOW,
                    HP_LIGHT_GREEN,
                    (float) ((health - 13.0) / 8.0)
            );
        }
        return HP_DARK_GREEN;
    }

    private static int lerpColor(int first, int second, float progress) {
        progress = Math.max(0.0F, Math.min(1.0F, progress));
        int alpha = Math.round(
                ((first >>> 24) & 0xFF)
                        + (((second >>> 24) & 0xFF) - ((first >>> 24) & 0xFF)) * progress
        );
        int red = Math.round(
                ((first >>> 16) & 0xFF)
                        + (((second >>> 16) & 0xFF) - ((first >>> 16) & 0xFF)) * progress
        );
        int green = Math.round(
                ((first >>> 8) & 0xFF)
                        + (((second >>> 8) & 0xFF) - ((first >>> 8) & 0xFF)) * progress
        );
        int blue = Math.round(
                (first & 0xFF) + ((second & 0xFF) - (first & 0xFF)) * progress
        );
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static Identifier resolveSkin(MinecraftClient client, UUID uuid) {
        PlayerListEntry entry = client.getNetworkHandler() == null
                ? null
                : client.getNetworkHandler().getPlayerListEntry(uuid);

        if (entry != null) {
            try {
                Identifier skin = entry.getSkinTextures().texture();
                if (skin != null) {
                    SKIN_CACHE.put(uuid, skin);
                    return skin;
                }
            } catch (Throwable ignored) {
            }
        }

        return getSkinId(uuid);
    }

    private static Identifier getSkinId(UUID uuid) {
        Identifier cached = SKIN_CACHE.get(uuid);
        if (cached != null) return cached;

        LastSeen memory = LAST_SEEN.get(uuid);
        if (memory != null && memory.skinId != null) return memory.skinId;

        Identifier fallback = fallbackSkin(uuid);
        SKIN_CACHE.put(uuid, fallback);
        return fallback;
    }

    private static Identifier fallbackSkin(UUID uuid) {
        try {
            Method method = DefaultSkinHelper.class.getMethod("getTexture", UUID.class);
            return (Identifier) method.invoke(null, uuid);
        } catch (Throwable ignored) {
            return DefaultSkinHelper.getTexture();
        }
    }

    private static void drawHead(
            DrawContext context,
            Identifier skin,
            int x,
            int y,
            int width,
            int height
    ) {
        if (skin == null) return;

        context.drawTexture(
                RenderLayer::getGuiTextured,
                skin,
                x,
                y,
                8.0F,
                8.0F,
                width,
                height,
                64,
                64
        );
        context.drawTexture(
                RenderLayer::getGuiTextured,
                skin,
                x,
                y,
                40.0F,
                8.0F,
                width,
                height,
                64,
                64
        );
    }

    private static void cleanupOldEntries(long now) {
        long cutoff = now - LAST_SEEN_KEEP_MS * 2;
        LAST_SEEN.entrySet().removeIf(entry ->
                entry.getValue() == null || entry.getValue().timeMs < cutoff
        );
    }

    private static String sanitizeName(String value) {
        if (value == null) return "";
        return value
                .replaceAll("^(\\s*\\[[^\\]]+\\]\\s*)+", "")
                .replaceAll("§.", "")
                .replaceAll("[^A-Za-z0-9_]", "")
                .trim();
    }

    private static void forget(UUID uuid) {
        LAST_SEEN.remove(uuid);
        SKIN_CACHE.remove(uuid);
    }

    private static void forgetAll() {
        LAST_SEEN.clear();
        SKIN_CACHE.clear();
    }

    private record RenderLayout(int x, int y, int width, int height) {
    }

    private static final class LastSeen {
        final double x;
        final double y;
        final double z;
        final long timeMs;
        final Identifier skinId;
        final boolean sneaking;

        LastSeen(
                double x,
                double y,
                double z,
                long timeMs,
                Identifier skinId,
                boolean sneaking
        ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.timeMs = timeMs;
            this.skinId = skinId;
            this.sneaking = sneaking;
        }
    }

    private static final class Row {
        final UUID uuid;
        final String name;
        final Targeting.Affinity affinity;
        final boolean unloaded;
        final boolean stale;
        final double distance;
        final double deltaY;
        final double health;
        final double angleDegrees;
        final boolean sneaking;
        final int armor;

        private Row(
                UUID uuid,
                String name,
                Targeting.Affinity affinity,
                boolean unloaded,
                boolean stale,
                double distance,
                double deltaY,
                double health,
                double angleDegrees,
                boolean sneaking,
                int armor
        ) {
            this.uuid = uuid;
            this.name = name;
            this.affinity = affinity == null ? Targeting.Affinity.NEUTRAL : affinity;
            this.unloaded = unloaded;
            this.stale = stale;
            this.distance = distance;
            this.deltaY = deltaY;
            this.health = health;
            this.angleDegrees = angleDegrees;
            this.sneaking = sneaking;
            this.armor = armor;
        }

        static Row live(
                UUID uuid,
                String name,
                Targeting.Affinity affinity,
                double distance,
                double deltaY,
                double health,
                double angleDegrees,
                boolean sneaking,
                int armor
        ) {
            return new Row(
                    uuid,
                    name,
                    affinity,
                    false,
                    false,
                    distance,
                    deltaY,
                    health,
                    angleDegrees,
                    sneaking,
                    armor
            );
        }

        static Row unloaded(
                UUID uuid,
                String name,
                Targeting.Affinity affinity,
                double distance,
                double deltaY,
                double health,
                double angleDegrees,
                boolean stale,
                boolean sneaking,
                int armor
        ) {
            return new Row(
                    uuid,
                    name,
                    affinity,
                    true,
                    stale,
                    distance,
                    deltaY,
                    health,
                    angleDegrees,
                    sneaking,
                    armor
            );
        }

        static Row preview(
                UUID uuid,
                String name,
                Targeting.Affinity affinity,
                double distance,
                double deltaY,
                double health,
                double angleDegrees,
                boolean sneaking,
                int armor
        ) {
            return live(
                    uuid,
                    name,
                    affinity,
                    distance,
                    deltaY,
                    health,
                    angleDegrees,
                    sneaking,
                    armor
            );
        }

        String distanceDetail() {
            return Double.isNaN(distance)
                    ? "—"
                    : String.format(Locale.ROOT, "%.1f", distance);
        }

        String distanceCompact() {
            return Double.isNaN(distance)
                    ? "—"
                    : Long.toString(Math.round(distance));
        }

        String deltaYText() {
            if (Double.isNaN(deltaY)) return "—";
            long rounded = Math.round(deltaY);
            return (rounded > 0 ? "+" : "") + rounded;
        }

        String healthText() {
            if (Double.isNaN(health)) return "—";
            String value = String.format(Locale.ROOT, "%.1f", health);
            if (health <= 6.0) value += "!";
            return value + "♥";
        }

        String armorText() {
            return armor < 0 ? "—" : Integer.toString(armor);
        }
    }

    public static final class Targeting {
        public enum SortMode {
            ORDER,
            DISTANCE,
            HEALTH
        }

        public enum AddResult {
            ADDED,
            EXISTS,
            NOT_FOUND
        }

        public enum Affinity {
            NEUTRAL,
            TEAM,
            ENEMY;

            static Affinity fromCommand(String value) {
                if (value == null) return NEUTRAL;
                return switch (value.toLowerCase(Locale.ROOT)) {
                    case "team" -> TEAM;
                    case "enemy" -> ENEMY;
                    default -> NEUTRAL;
                };
            }
        }

        private static final LinkedHashMap<UUID, TargetInfo> TARGETS = new LinkedHashMap<>();
        private static SortMode sortMode = SortMode.ORDER;

        private Targeting() {
        }

        public static AddResult addOrEnsureByName(String name) {
            return addOrEnsureByName(name, Affinity.NEUTRAL);
        }

        public static AddResult addOrEnsureByName(String rawName, Affinity affinity) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getNetworkHandler() == null) {
                return AddResult.NOT_FOUND;
            }

            String needle = sanitizeName(rawName).toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) return AddResult.NOT_FOUND;

            for (TargetInfo target : TARGETS.values()) {
                if (target.name.equalsIgnoreCase(needle)) {
                    target.affinity = affinity == null ? Affinity.NEUTRAL : affinity;
                    saveSettings();
                    return AddResult.EXISTS;
                }
            }

            UUID foundUuid = null;
            String foundName = null;

            for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                if (entry.getProfile() == null) continue;

                String profileName = entry.getProfile().getName();
                String profileLower = profileName == null
                        ? ""
                        : profileName.toLowerCase(Locale.ROOT);
                String displayLower = entry.getDisplayName() == null
                        ? ""
                        : sanitizeName(entry.getDisplayName().getString()).toLowerCase(Locale.ROOT);

                if (profileLower.equals(needle) || displayLower.equals(needle)) {
                    foundUuid = entry.getProfile().getId();
                    foundName = profileName;
                    break;
                }
            }

            if (foundUuid == null) {
                for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                    if (entry.getProfile() == null) continue;

                    String profileName = entry.getProfile().getName();
                    String profileLower = profileName == null
                            ? ""
                            : profileName.toLowerCase(Locale.ROOT);
                    String displayLower = entry.getDisplayName() == null
                            ? ""
                            : sanitizeName(entry.getDisplayName().getString()).toLowerCase(Locale.ROOT);

                    if (profileLower.contains(needle) || displayLower.contains(needle)) {
                        foundUuid = entry.getProfile().getId();
                        foundName = profileName;
                        break;
                    }
                }
            }

            if (foundUuid == null) return AddResult.NOT_FOUND;

            TARGETS.put(
                    foundUuid,
                    new TargetInfo(
                            foundUuid,
                            foundName == null ? needle : foundName,
                            affinity == null ? Affinity.NEUTRAL : affinity
                    )
            );
            saveSettings();
            return AddResult.ADDED;
        }

        public static boolean removeByName(String rawName) {
            String needle = sanitizeName(rawName).toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) return false;

            UUID match = null;
            for (Map.Entry<UUID, TargetInfo> entry : TARGETS.entrySet()) {
                String lower = entry.getValue().name.toLowerCase(Locale.ROOT);
                if (lower.equals(needle) || lower.contains(needle)) {
                    match = entry.getKey();
                    break;
                }
            }

            if (match == null) return false;
            TARGETS.remove(match);
            forget(match);
            saveSettings();
            return true;
        }

        public static void clearAll() {
            TARGETS.clear();
            forgetAll();
            saveSettings();
        }

        public static void setSortMode(SortMode mode) {
            sortMode = mode == null ? SortMode.ORDER : mode;
            saveSettings();
            beep(1.05F);
        }

        public static SortMode getSortMode() {
            return sortMode;
        }

        public static Collection<TargetInfo> all() {
            return TARGETS.values();
        }

        public static final class TargetInfo {
            public final UUID uuid;
            public String name;
            public Affinity affinity;

            TargetInfo(UUID uuid, String name, Affinity affinity) {
                this.uuid = uuid;
                this.name = name;
                this.affinity = affinity == null ? Affinity.NEUTRAL : affinity;
            }
        }
    }
}
