package untitled.untitled.client;

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
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

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
    private static final int SEPARATOR_COLOR = 0x35FFFFFF;
    private static final int SNEAK_COLOR = 0xFF7FE4FF;
    private static final int ARROW_COLOR = 0xFFFFFFFF;
    private static final int ARROW_COLOR_MUTED = 0x90AAAAAA;

    private static final int HP_RED = 0xFFFF5A5A;
    private static final int HP_YELLOW = 0xFFFFD56A;
    private static final int HP_LIGHT_GREEN = 0xFFB9F6A5;
    private static final int HP_DARK_GREEN = 0xFF4FD37E;

    private static final int MAX_ROWS_DETAIL = 7;
    private static final int MAX_ROWS_COMPACT = 4;
    private static final int ROW_GAP = 6;
    private static final int COL_GAP = 14;
    private static final int PAD_X = 10;
    private static final int PAD_Y = 8;
    private static final int ICON_GAP = 5;
    private static final int ARMOR_GAP = 5;
    private static final int TEXT_VERTICAL_NUDGE = 1;
    private static final int SOFT_MARGIN = 2;

    private static final long LAST_SEEN_KEEP_MS = 15_000L;
    private static final long CLEANUP_INTERVAL_MS = 5_000L;

    private static final Map<UUID, LastSeen> LAST_SEEN = new HashMap<>();
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
    private static long lastCleanupMs = 0L;
    private static int offsetX = 0;
    private static int offsetY = 0;
    private static EditHud.HudBounds lastEditorBounds = new EditHud.HudBounds(0, 0, 1, 1);

    private PartyHud() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        HudRenderCallback.EVENT.register(PartyHud::render);
        registerCommands();
    }

    public static void setModeDetail() {
        viewMode = ViewMode.DETAIL;
        saveSettings();
    }

    public static void setModeCompact() {
        viewMode = ViewMode.COMPACT;
        saveSettings();
    }

    public static ViewMode getMode() {
        return viewMode;
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

    static EditHud.HudBounds getEditorBounds() {
        return lastEditorBounds;
    }

    static void renderEditorPreview(DrawContext context) {
        RenderLayout layout = renderRows(context, EDITOR_ROWS);
        if (layout != null) {
            lastEditorBounds = new EditHud.HudBounds(layout.x, layout.y, layout.width, layout.height);
        }
    }

    static void writeSettings(JsonObject root) {
        root.addProperty("partyViewMode", viewMode.name());
        root.addProperty("partySortMode", Targeting.getSortMode().name());

        JsonArray targets = new JsonArray();
        for (Targeting.TargetInfo target : Targeting.all()) {
            JsonObject item = new JsonObject();
            item.addProperty("uuid", target.uuid.toString());
            item.addProperty("name", target.name);
            item.addProperty("affinity", target.affinity.name());
            targets.add(item);
        }
        root.add("partyTargets", targets);
    }

    static void readSettings(JsonObject root) {
        loadingSettings = true;
        try {
            if (root.has("partyViewMode")) {
                try {
                    viewMode = ViewMode.valueOf(root.get("partyViewMode").getAsString());
                } catch (IllegalArgumentException ignored) {
                    viewMode = ViewMode.COMPACT;
                }
            }

            if (root.has("partySortMode")) {
                try {
                    Targeting.sortMode = Targeting.SortMode.valueOf(
                            root.get("partySortMode").getAsString()
                    );
                } catch (IllegalArgumentException ignored) {
                    Targeting.sortMode = Targeting.SortMode.ORDER;
                }
            }

            Targeting.TARGETS.clear();
            if (root.has("partyTargets") && root.get("partyTargets").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("partyTargets")) {
                    if (!element.isJsonObject() || Targeting.TARGETS.size() >= Targeting.MAXIMUM_TARGETS) {
                        continue;
                    }

                    JsonObject item = element.getAsJsonObject();
                    if (!item.has("uuid") || !item.has("name")) {
                        continue;
                    }

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
            EditHud.saveSettings();
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
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            String name = StringArgumentType.getString(context, "player");
                                            String role = StringArgumentType.getString(context, "role");
                                            Targeting.Affinity affinity = role.equalsIgnoreCase("team")
                                                    ? Targeting.Affinity.TEAM
                                                    : role.equalsIgnoreCase("enemy")
                                                    ? Targeting.Affinity.ENEMY
                                                    : Targeting.Affinity.NEUTRAL;
                                            return resultCode(Targeting.addOrEnsureByName(name, affinity));
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
                                .then(literal("distance").executes(context -> {
                                    Targeting.setSortModeDistance();
                                    beep(1.05F);
                                    return 1;
                                }))
                                .then(literal("health").executes(context -> {
                                    Targeting.setSortModeHealth();
                                    beep(1.05F);
                                    return 1;
                                }))
                                .then(literal("order").executes(context -> {
                                    Targeting.setSortModeOrder();
                                    beep(1.0F);
                                    return 1;
                                })))
                        .then(literal("mode")
                                .then(literal("detail").executes(context -> {
                                    setModeDetail();
                                    beep(1.0F);
                                    return 1;
                                }))
                                .then(literal("compact").executes(context -> {
                                    setModeCompact();
                                    beep(1.1F);
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
            case FULL, NOT_FOUND -> {
                beep(0.9F);
                yield 0;
            }
        };
    }

    private static void beep(float pitch) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
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
                if (entry.getProfile() == null) {
                    continue;
                }
                String name = entry.getProfile().getName();
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        if (client != null && client.player != null) {
            names.add(client.player.getGameProfile().getName());
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
        if (client == null || client.player == null || client.world == null) {
            return;
        }
        if (client.currentScreen instanceof EditHud
                || client.options.hudHidden
                || Targeting.all().isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCleanupMs > CLEANUP_INTERVAL_MS) {
            cleanupOldEntries();
            lastCleanupMs = now;
        }

        float tickDelta = tickCounter.getTickDelta(false);
        List<Row> rows = sortAndLimit(buildRows(client, tickDelta, now));
        renderRows(context, rows);
    }

    private static List<Row> buildRows(MinecraftClient client, float tickDelta, long now) {
        Vec3d forward = client.player.getRotationVec(tickDelta);
        double forwardX = forward.x;
        double forwardZ = forward.z;
        List<Row> rows = new ArrayList<>();

        for (Targeting.TargetInfo target : Targeting.all()) {
            UUID uuid = target.uuid;
            PlayerEntity player = client.world.getPlayerByUuid(uuid);

            if (player != null) {
                double dx = player.getX() - client.player.getX();
                double dy = player.getY() - client.player.getY();
                double dz = player.getZ() - client.player.getZ();
                double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double dot = forwardX * dx + forwardZ * dz;
                double cross = forwardX * dz - forwardZ * dx;
                double angle = Math.toDegrees(Math.atan2(cross, dot));
                double health = player.getHealth() + player.getAbsorptionAmount();
                boolean sneaking = player.isSneaking();
                int armor = player.getArmor();

                Identifier skin = resolveLiveSkin(uuid);
                cacheSkin(uuid, skin);
                LAST_SEEN.put(uuid, new LastSeen(
                        player.getX(), player.getY(), player.getZ(), now, skin, sneaking
                ));

                rows.add(Row.live(
                        uuid, target.name, target.affinity,
                        distance, dy, health, angle, sneaking, armor
                ));
                continue;
            }

            LastSeen memory = LAST_SEEN.get(uuid);
            if (memory == null) {
                cacheSkin(uuid, null);
                rows.add(Row.unloaded(
                        uuid, target.name, target.affinity,
                        Double.NaN, Double.NaN, Double.NaN,
                        0.0, true, false, false, -1
                ));
                continue;
            }

            long age = now - memory.timeMs;
            boolean stale = age > LAST_SEEN_KEEP_MS;
            if (memory.skinId == null) {
                memory.skinId = fallbackSkin(uuid);
            }

            double distance = Double.NaN;
            double dy = Double.NaN;
            double angle = 0.0;
            boolean showSneak = false;

            if (!stale) {
                double dx = memory.x - client.player.getX();
                dy = memory.y - client.player.getY();
                double dz = memory.z - client.player.getZ();
                distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double dot = forwardX * dx + forwardZ * dz;
                double cross = forwardX * dz - forwardZ * dx;
                angle = Math.toDegrees(Math.atan2(cross, dot));
                showSneak = memory.sneaking;
            }

            rows.add(Row.unloaded(
                    uuid, target.name, target.affinity,
                    distance, dy, Double.NaN, angle,
                    stale, showSneak, showSneak, -1
            ));
        }

        return rows;
    }

    private static List<Row> sortAndLimit(List<Row> source) {
        List<Row> rows = new ArrayList<>(source);
        if (Targeting.getSortMode() == Targeting.SortMode.DISTANCE) {
            rows.sort(Comparator
                    .comparing(Row::isUnloaded)
                    .thenComparingDouble(row -> Double.isNaN(row.distance)
                            ? Double.POSITIVE_INFINITY
                            : row.distance));
        } else if (Targeting.getSortMode() == Targeting.SortMode.HEALTH) {
            rows.sort(Comparator
                    .comparing(Row::isUnloaded)
                    .thenComparingDouble(row -> Double.isNaN(row.health)
                            ? Double.POSITIVE_INFINITY
                            : row.health));
        }

        int maximum = viewMode == ViewMode.COMPACT ? MAX_ROWS_COMPACT : MAX_ROWS_DETAIL;
        if (rows.size() > maximum) {
            return new ArrayList<>(rows.subList(0, maximum));
        }
        return rows;
    }

    private static RenderLayout renderRows(DrawContext context, List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return null;
        }

        TextRenderer font = client.textRenderer;
        int lineHeight = font.fontHeight;
        int iconHeight = Math.max(8, lineHeight - 1);
        int iconWidth = iconHeight;
        int arrowBox = Math.max(14, lineHeight);
        int nameSuffixWidth = font.getWidth("⏷") + 1;
        int nameWidth = 0;
        int distanceWidth = 0;
        int deltaYWidth = 0;
        int healthWidth = 0;
        int armorWidth = viewMode == ViewMode.DETAIL
                ? Math.max(font.getWidth("0"), font.getWidth("20"))
                : 0;

        for (Row row : rows) {
            nameWidth = Math.max(nameWidth, font.getWidth(row.name) + nameSuffixWidth);
            distanceWidth = Math.max(distanceWidth, font.getWidth(
                    viewMode == ViewMode.COMPACT ? row.distanceCompact() : row.distanceDetail()
            ));
            if (viewMode == ViewMode.DETAIL) {
                deltaYWidth = Math.max(deltaYWidth, font.getWidth(row.deltaYText()));
            }
            healthWidth = Math.max(healthWidth, font.getWidth(row.healthText()));
        }

        int rowGap = viewMode == ViewMode.COMPACT ? 4 : ROW_GAP;
        int columnGap = viewMode == ViewMode.COMPACT ? 8 : COL_GAP;
        int nameColumnWidth;
        int contentWidth;

        if (viewMode == ViewMode.DETAIL) {
            nameColumnWidth = armorWidth + ARMOR_GAP + iconWidth + ICON_GAP + nameWidth;
            contentWidth = nameColumnWidth
                    + columnGap + distanceWidth
                    + columnGap + deltaYWidth
                    + columnGap + healthWidth
                    + columnGap + arrowBox;
        } else {
            nameColumnWidth = iconWidth + ICON_GAP + nameWidth;
            contentWidth = nameColumnWidth
                    + columnGap + distanceWidth
                    + columnGap + healthWidth
                    + columnGap + arrowBox;
        }

        int contentHeight = rows.size() * lineHeight + Math.max(0, rows.size() - 1) * rowGap;
        int width = contentWidth + PAD_X * 2;
        int height = contentHeight + PAD_Y * 2;
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int x = (screenWidth - width) / 2 + offsetX;
        int y = screenHeight - 60 + offsetY;
        x = clampSoft(x, width, screenWidth, SOFT_MARGIN);
        y = clampSoft(y, height, screenHeight, SOFT_MARGIN);

        int armorColumn = x + PAD_X;
        int headColumn;
        int nameColumn;
        int distanceColumn;
        int deltaYColumn = 0;
        int healthColumn;
        int arrowColumn;

        if (viewMode == ViewMode.DETAIL) {
            headColumn = armorColumn + armorWidth + ARMOR_GAP;
            nameColumn = headColumn + iconWidth + ICON_GAP;
            distanceColumn = x + PAD_X + nameColumnWidth + columnGap;
            deltaYColumn = distanceColumn + distanceWidth + columnGap;
            healthColumn = deltaYColumn + deltaYWidth + columnGap;
            arrowColumn = healthColumn + healthWidth + columnGap;
        } else {
            headColumn = x + PAD_X;
            nameColumn = headColumn + iconWidth + ICON_GAP;
            distanceColumn = headColumn + nameColumnWidth + columnGap;
            healthColumn = distanceColumn + distanceWidth + columnGap;
            arrowColumn = healthColumn + healthWidth + columnGap;
        }

        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            int rawY = y + PAD_Y + index * (lineHeight + rowGap);
            int textY = rawY + TEXT_VERTICAL_NUDGE;
            boolean unloaded = row.unloaded;

            int baseNameColor = switch (row.affinity) {
                case TEAM -> NAME_TEAM;
                case ENEMY -> NAME_ENEMY;
                case NEUTRAL -> NAME_IDLE;
            };
            int nameColor = unloaded ? MUTED : baseNameColor;
            int distanceColor = unloaded ? MUTED : DIST_COLOR;
            int deltaColor = unloaded ? MUTED : DY_COLOR;
            int healthColor = unloaded || Double.isNaN(row.health) ? MUTED : healthColor(row.health);

            if (viewMode == ViewMode.DETAIL) {
                String armor = row.armorText();
                int armorTextWidth = font.getWidth(armor);
                drawColoredText(
                        context,
                        font,
                        armor,
                        armorColumn + Math.max(0, armorWidth - armorTextWidth),
                        textY,
                        unloaded ? MUTED : ARMOR_COLOR
                );
            }

            drawHead(context, getSkinIdFor(row.uuid), headColumn, textY, iconWidth, iconHeight);
            drawColoredText(context, font, row.name, nameColumn, textY, nameColor);
            drawSneakSuffix(context, font, row, nameColumn, textY);
            drawColoredText(
                    context,
                    font,
                    viewMode == ViewMode.COMPACT ? row.distanceCompact() : row.distanceDetail(),
                    distanceColumn,
                    textY,
                    distanceColor
            );

            if (viewMode == ViewMode.DETAIL) {
                drawColoredText(context, font, row.deltaYText(), deltaYColumn, textY, deltaColor);
            }

            drawColoredText(context, font, row.healthText(), healthColumn, textY, healthColor);

            int arrowY = textY + (lineHeight - arrowBox) / 2;
            boolean self = client.player != null && row.uuid.equals(client.player.getUuid());
            if (row.stale || self) {
                drawDash(
                        context,
                        font,
                        arrowColumn,
                        arrowY,
                        arrowBox,
                        unloaded ? MUTED : DIST_COLOR
                );
            } else {
                NeonArrow.drawRotated(
                        context,
                        arrowColumn,
                        arrowY,
                        arrowBox,
                        arrowBox,
                        row.angleDegrees,
                        unloaded ? ARROW_COLOR_MUTED : ARROW_COLOR
                );
            }

            if (index < rows.size() - 1) {
                int separatorY = rawY + lineHeight + rowGap / 2;
                context.fill(
                        headColumn,
                        separatorY,
                        Math.max(arrowColumn + arrowBox, headColumn + contentWidth),
                        separatorY + 1,
                        SEPARATOR_COLOR
                );
            }
        }

        return new RenderLayout(x, y, width, height);
    }

    private static void drawColoredText(
            DrawContext context,
            TextRenderer font,
            String value,
            int x,
            int y,
            int argb
    ) {
        if (value == null || value.isEmpty()) {
            return;
        }
        Text text = Text.literal(value).styled(style ->
                style.withColor(TextColor.fromRgb(argb & 0xFFFFFF))
        );
        context.drawTextWithShadow(font, text, x, y, 0xFFFFFF);
    }

    private static void drawSneakSuffix(
            DrawContext context,
            TextRenderer font,
            Row row,
            int nameX,
            int y
    ) {
        if (!row.sneaking || row.stale) {
            return;
        }
        int color = row.sneakGhost ? dimRgb(SNEAK_COLOR, 0.6) : SNEAK_COLOR;
        drawColoredText(context, font, "⏷", nameX + font.getWidth(row.name) + 1, y, color);
    }

    private static void drawDash(
            DrawContext context,
            TextRenderer font,
            int x,
            int y,
            int size,
            int color
    ) {
        String dash = "—";
        int drawX = x + (size - font.getWidth(dash)) / 2;
        int drawY = y + (size - font.fontHeight) / 2 + TEXT_VERTICAL_NUDGE;
        drawColoredText(context, font, dash, drawX, drawY, color);
    }

    private static int healthColor(double health) {
        if (health <= 6.0) {
            return HP_RED;
        }
        if (health < 13.0) {
            return lerpColor(HP_RED, HP_YELLOW, (float) ((health - 6.0) / 7.0));
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

    private static int multiplyAlpha(int argb, double factor) {
        int alpha = (int) Math.round(((argb >>> 24) & 0xFF) * factor);
        alpha = Math.max(0, Math.min(255, alpha));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    private static int dimRgb(int argb, double factor) {
        factor = Math.max(0.0, Math.min(1.0, factor));
        int alpha = (argb >>> 24) & 0xFF;
        int red = (int) Math.round(((argb >>> 16) & 0xFF) * factor);
        int green = (int) Math.round(((argb >>> 8) & 0xFF) * factor);
        int blue = (int) Math.round((argb & 0xFF) * factor);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static Identifier getSkinIdFor(UUID uuid) {
        LastSeen memory = LAST_SEEN.get(uuid);
        if (memory != null && memory.skinId != null) {
            return memory.skinId;
        }
        Identifier live = resolveLiveSkin(uuid);
        if (live != null) {
            cacheSkin(uuid, live);
            return live;
        }
        return fallbackSkin(uuid);
    }

    private static Identifier resolveLiveSkin(UUID uuid) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.getNetworkHandler() != null) {
                PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(uuid);
                if (entry != null
                        && entry.getSkinTextures() != null
                        && entry.getSkinTextures().texture() != null) {
                    return entry.getSkinTextures().texture();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void cacheSkin(UUID uuid, Identifier skin) {
        LastSeen memory = LAST_SEEN.get(uuid);
        if (memory == null) {
            memory = new LastSeen(0.0, 0.0, 0.0, 0L, null, false);
            LAST_SEEN.put(uuid, memory);
        }
        if (skin != null) {
            memory.skinId = skin;
        }
    }

    private static Identifier fallbackSkin(UUID uuid) {
        try {
            try {
                java.lang.reflect.Method method =
                        DefaultSkinHelper.class.getMethod("getTexture", UUID.class);
                return (Identifier) method.invoke(null, uuid);
            } catch (NoSuchMethodException ignored) {
                return DefaultSkinHelper.getTexture();
            }
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
        if (skin == null) {
            return;
        }
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

    private static int clampSoft(int position, int size, int screen, int margin) {
        int minimum = margin - size;
        int maximum = screen - margin;
        return Math.max(minimum, Math.min(maximum, position));
    }

    private static void cleanupOldEntries() {
        long cutoff = System.currentTimeMillis() - LAST_SEEN_KEEP_MS * 2;
        LAST_SEEN.entrySet().removeIf(entry ->
                entry.getValue() == null || entry.getValue().timeMs < cutoff
        );
    }

    private static String sanitizeNameForMatching(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("^(\\s*\\[[^\\]]+\\]\\s*)+", "")
                .replaceAll("§.", "")
                .replaceAll("[^A-Za-z0-9_]", "")
                .trim();
    }

    private static void forget(UUID uuid) {
        if (uuid != null) {
            LAST_SEEN.remove(uuid);
        }
    }

    private static void forgetAll() {
        LAST_SEEN.clear();
    }

    private record RenderLayout(int x, int y, int width, int height) {
    }

    private static final class LastSeen {
        double x;
        double y;
        double z;
        long timeMs;
        Identifier skinId;
        boolean sneaking;

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
        final boolean sneakGhost;
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
                boolean sneakGhost,
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
            this.sneakGhost = sneakGhost;
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
                    false,
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
                boolean sneakGhost,
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
                    sneakGhost,
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

        boolean isUnloaded() {
            return unloaded;
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
            if (Double.isNaN(deltaY)) {
                return "—";
            }
            long rounded = Math.round(deltaY);
            return (rounded > 0 ? "+" : "") + rounded;
        }

        String healthText() {
            if (Double.isNaN(health)) {
                return "—";
            }
            String value = String.format(Locale.ROOT, "%.1f", health);
            if (health <= 6.0) {
                value += "!";
            }
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
            FULL,
            NOT_FOUND
        }

        public enum Affinity {
            NEUTRAL,
            TEAM,
            ENEMY
        }

        private static final LinkedHashMap<UUID, TargetInfo> TARGETS =
                new LinkedHashMap<>();
        private static final int MAXIMUM_TARGETS = 7;
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

            String needle = sanitizeNameForMatching(rawName).toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) {
                return AddResult.NOT_FOUND;
            }

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
                if (entry.getProfile() == null) {
                    continue;
                }

                String profileName = entry.getProfile().getName();
                String profileLower = profileName == null
                        ? ""
                        : profileName.toLowerCase(Locale.ROOT);
                String displayLower = entry.getDisplayName() == null
                        ? ""
                        : sanitizeNameForMatching(
                                entry.getDisplayName().getString()
                        ).toLowerCase(Locale.ROOT);

                if (profileLower.equals(needle) || displayLower.equals(needle)) {
                    foundUuid = entry.getProfile().getId();
                    foundName = profileName;
                    break;
                }
            }

            if (foundUuid == null) {
                for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                    if (entry.getProfile() == null) {
                        continue;
                    }

                    String profileName = entry.getProfile().getName();
                    String profileLower = profileName == null
                            ? ""
                            : profileName.toLowerCase(Locale.ROOT);
                    String displayLower = entry.getDisplayName() == null
                            ? ""
                            : sanitizeNameForMatching(
                                    entry.getDisplayName().getString()
                            ).toLowerCase(Locale.ROOT);

                    if (profileLower.contains(needle) || displayLower.contains(needle)) {
                        foundUuid = entry.getProfile().getId();
                        foundName = profileName;
                        break;
                    }
                }
            }

            if (foundUuid == null) {
                return AddResult.NOT_FOUND;
            }
            if (TARGETS.size() >= MAXIMUM_TARGETS) {
                return AddResult.FULL;
            }

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
            String needle = sanitizeNameForMatching(rawName).toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) {
                return false;
            }

            UUID match = null;
            for (Map.Entry<UUID, TargetInfo> entry : TARGETS.entrySet()) {
                String lower = entry.getValue().name.toLowerCase(Locale.ROOT);
                if (lower.equals(needle) || lower.contains(needle)) {
                    match = entry.getKey();
                    break;
                }
            }
            if (match == null) {
                return false;
            }

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

        public static void setSortModeOrder() {
            sortMode = SortMode.ORDER;
            saveSettings();
        }

        public static void setSortModeDistance() {
            sortMode = SortMode.DISTANCE;
            saveSettings();
        }

        public static void setSortModeHealth() {
            sortMode = SortMode.HEALTH;
            saveSettings();
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

    private static final class NeonArrow {
        private NeonArrow() {
        }

        static void drawRotated(
                DrawContext context,
                int x,
                int y,
                int width,
                int height,
                double angleDegrees,
                int argb
        ) {
            float centerX = x + width / 2.0F;
            float centerY = y + height / 2.0F;
            var matrices = context.getMatrices();
            matrices.push();
            matrices.translate(centerX, centerY, 0.0F);
            matrices.multiply(
                    RotationAxis.POSITIVE_Z.rotationDegrees((float) (angleDegrees - 90.0))
            );
            matrices.translate(-width / 2.0F, -height / 2.0F, 0.0F);

            int coreThickness = Math.max(1, width / 12);
            int outlineThickness = coreThickness + 1;
            int coreColor = multiplyAlpha(argb, 0.75);
            int outlineColor = 0xFF000000;

            int firstX1 = Math.round(width * 0.25F);
            int firstY1 = Math.round(height * 0.30F);
            int tipX = Math.round(width * 0.75F);
            int tipY = Math.round(height * 0.50F);
            int secondX1 = Math.round(width * 0.25F);
            int secondY1 = Math.round(height * 0.70F);

            drawLineThick(
                    context,
                    firstX1,
                    firstY1,
                    tipX,
                    tipY,
                    outlineThickness,
                    outlineColor
            );
            drawLineThick(
                    context,
                    secondX1,
                    secondY1,
                    tipX,
                    tipY,
                    outlineThickness,
                    outlineColor
            );
            drawLineThick(
                    context,
                    firstX1,
                    firstY1,
                    tipX,
                    tipY,
                    coreThickness,
                    coreColor
            );
            drawLineThick(
                    context,
                    secondX1,
                    secondY1,
                    tipX,
                    tipY,
                    coreThickness,
                    coreColor
            );
            matrices.pop();
        }

        private static void drawLineThick(
                DrawContext context,
                int x1,
                int y1,
                int x2,
                int y2,
                int thickness,
                int argb
        ) {
            int dx = Math.abs(x2 - x1);
            int dy = Math.abs(y2 - y1);
            int sx = x1 < x2 ? 1 : -1;
            int sy = y1 < y2 ? 1 : -1;
            int error = dx - dy;
            int radius = Math.max(1, thickness / 2);
            int x = x1;
            int y = y1;

            while (true) {
                context.fill(
                        x - radius,
                        y - radius,
                        x + radius + 1,
                        y + radius + 1,
                        argb
                );
                if (x == x2 && y == y2) {
                    break;
                }

                int doubled = 2 * error;
                if (doubled > -dy) {
                    error -= dy;
                    x += sx;
                }
                if (doubled < dx) {
                    error += dx;
                    y += sy;
                }
            }
        }
    }
}
