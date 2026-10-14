package untitled.untitled.client;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Client-only Minecraft title/subtitle rendering preferences.
 * The server messages, durations and vanilla fade timer are never modified.
 */
public final class TitleHud {
    private static final float BASE_TITLE_SCALE = 4.0F;
    private static final float BASE_SUBTITLE_SCALE = 2.0F;
    private static final float MIN_SIZE = 0.1F;
    private static final float MAX_SIZE = 5.0F;
    // Vanilla title text starts at centerY-40 and subtitle at centerY+10.
    private static final int TITLE_Y = -40;
    private static final int SUBTITLE_Y = 10;
    private static final int MARGIN = 2;

    private static boolean initialized;
    private static boolean titleVisible = true;
    private static boolean subtitleVisible = true;
    private static float titleSize = 1.0F;
    private static float subtitleSize = 1.0F;
    private static int titleOffsetX;
    private static int titleOffsetY;
    private static int subtitleOffsetX;
    private static int subtitleOffsetY;
    private static int editorDelayTicks = -1;

    private static final Text EDIT_TITLE = Text.literal("TITLE");
    private static final Text EDIT_SUBTITLE = Text.literal("SUBTITLE");

    private TitleHud() {}

    public static void init() {
        if (initialized) return;
        initialized = true;

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            dispatcher.register(literal("th")
                    .then(literal("toggle").executes(ctx -> {
                        // If either is visible, the group toggle hides both.
                        boolean show = !titleVisible && !subtitleVisible;
                        titleVisible = show;
                        subtitleVisible = show;
                        EditHud.saveSettings();
                        return 1;
                    }))
                    .then(literal("size")
                            .then(argument("value", FloatArgumentType.floatArg(MIN_SIZE, MAX_SIZE))
                                    .executes(ctx -> {
                                        float value = FloatArgumentType.getFloat(ctx, "value");
                                        titleSize = value;
                                        subtitleSize = value;
                                        EditHud.saveSettings();
                                        return 1;
                                    })))
                    .then(literal("title")
                            .then(literal("toggle").executes(ctx -> {
                                titleVisible = !titleVisible;
                                EditHud.saveSettings();
                                return 1;
                            }))
                            .then(literal("size")
                                    .then(argument("value", FloatArgumentType.floatArg(MIN_SIZE, MAX_SIZE))
                                            .executes(ctx -> {
                                                titleSize = FloatArgumentType.getFloat(ctx, "value");
                                                EditHud.saveSettings();
                                                return 1;
                                            }))))
                    .then(literal("subtitle")
                            .then(literal("toggle").executes(ctx -> {
                                subtitleVisible = !subtitleVisible;
                                EditHud.saveSettings();
                                return 1;
                            }))
                            .then(literal("size")
                                    .then(argument("value", FloatArgumentType.floatArg(MIN_SIZE, MAX_SIZE))
                                            .executes(ctx -> {
                                                subtitleSize = FloatArgumentType.getFloat(ctx, "value");
                                                EditHud.saveSettings();
                                                return 1;
                                            })))));
            dispatcher.register(literal("thud")
                    .executes(ctx -> {
                        editorDelayTicks = 2;
                        return 1;
                    })
                    .then(literal("reset").executes(ctx -> {
                        titleOffsetX = 0;
                        titleOffsetY = 0;
                        subtitleOffsetX = 0;
                        subtitleOffsetY = 0;
                        EditHud.saveSettings();
                        return 1;
                    })));
        });

        // Open on the client tick instead of inside the command dispatch.
        // This mirrors the existing /hud editor and keeps chat focus intact.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (editorDelayTicks < 0) return;
            if (editorDelayTicks > 0) {
                editorDelayTicks--;
                return;
            }
            editorDelayTicks = -1;
            if (!(client.currentScreen instanceof TitleHudScreen)) {
                client.setScreen(new TitleHudScreen());
            }
        });
    }

    public static boolean isEditorOpen() {
        return MinecraftClient.getInstance().currentScreen instanceof TitleHudScreen;
    }

    /**
     * Replaces only vanilla title/subtitle drawing. Non-title HUD elements
     * (chat, actionbar, hotbar, bossbar) remain untouched.
     */
    public static void render(DrawContext context, Text title, Text subtitle, int alpha) {
        if (isEditorOpen() || alpha <= 0) return;
        if (titleVisible && title != null) {
            drawText(context, title, true, alpha);
        }
        if (subtitleVisible && subtitle != null) {
            drawText(context, subtitle, false, alpha);
        }
    }

    static void renderEditorPreview(DrawContext context) {
        drawText(context, EDIT_TITLE, true, 255);
        drawText(context, EDIT_SUBTITLE, false, 255);
    }

    private static void drawText(DrawContext context, Text text, boolean main, int alpha) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;
        float scale = (main ? BASE_TITLE_SCALE * titleSize
                            : BASE_SUBTITLE_SCALE * subtitleSize);
        int x = context.getScaledWindowWidth() / 2
                + (main ? titleOffsetX : subtitleOffsetX);
        int y = context.getScaledWindowHeight() / 2
                + (main ? TITLE_Y + titleOffsetY : SUBTITLE_Y + subtitleOffsetY);

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x, y, 0.0F);
        matrices.scale(scale, scale, 1.0F);
        try {
            // Preserve Text styles and the server-supplied color.
            context.drawTextWithShadow(font, text, -font.getWidth(text) / 2, 0,
                    (Math.min(255, Math.max(0, alpha)) << 24) | 0xFFFFFF);
        } finally {
            matrices.pop();
        }
    }

    private static EditHud.HudBounds editorBounds(boolean main) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;
        Text preview = main ? EDIT_TITLE : EDIT_SUBTITLE;
        float scale = main ? BASE_TITLE_SCALE * titleSize : BASE_SUBTITLE_SCALE * subtitleSize;
        int width = Math.max(1, (int) Math.ceil(font.getWidth(preview) * scale));
        int height = Math.max(1, (int) Math.ceil(font.fontHeight * scale));
        int x = client.getWindow().getScaledWidth() / 2
                + (main ? titleOffsetX : subtitleOffsetX);
        int y = client.getWindow().getScaledHeight() / 2
                + (main ? TITLE_Y + titleOffsetY : SUBTITLE_Y + subtitleOffsetY);
        return new EditHud.HudBounds(x - width / 2, y, width, height);
    }

    static EditHud.HudBounds getTitleEditorBounds() {
        return editorBounds(true);
    }

    static EditHud.HudBounds getSubtitleEditorBounds() {
        return editorBounds(false);
    }

    static void moveBy(boolean main, int dx, int dy) {
        if (main) {
            titleOffsetX += dx;
            titleOffsetY += dy;
        } else {
            subtitleOffsetX += dx;
            subtitleOffsetY += dy;
        }
        clampPosition(main);
    }

    private static void clampPosition(boolean main) {
        MinecraftClient client = MinecraftClient.getInstance();
        EditHud.HudBounds bounds = editorBounds(main);
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int dx = 0, dy = 0;
        if (bounds.width() <= sw - 2 * MARGIN) {
            if (bounds.x() < MARGIN) dx = MARGIN - bounds.x();
            if (bounds.x() + bounds.width() > sw - MARGIN)
                dx = sw - MARGIN - bounds.x() - bounds.width();
        }
        if (bounds.height() <= sh - 2 * MARGIN) {
            if (bounds.y() < MARGIN) dy = MARGIN - bounds.y();
            if (bounds.y() + bounds.height() > sh - MARGIN)
                dy = sh - MARGIN - bounds.y() - bounds.height();
        }
        if (main) {
            titleOffsetX += dx;
            titleOffsetY += dy;
        } else {
            subtitleOffsetX += dx;
            subtitleOffsetY += dy;
        }
    }

    static void writeSettings(JsonObject root) {
        root.addProperty("titleHudTitleVisible", titleVisible);
        root.addProperty("titleHudSubtitleVisible", subtitleVisible);
        root.addProperty("titleHudTitleSize", titleSize);
        root.addProperty("titleHudSubtitleSize", subtitleSize);
        root.addProperty("titleHudTitleOffsetX", titleOffsetX);
        root.addProperty("titleHudTitleOffsetY", titleOffsetY);
        root.addProperty("titleHudSubtitleOffsetX", subtitleOffsetX);
        root.addProperty("titleHudSubtitleOffsetY", subtitleOffsetY);
    }

    static void readSettings(JsonObject root) {
        if (root.has("titleHudTitleVisible")) {
            try { titleVisible = root.get("titleHudTitleVisible").getAsBoolean(); }
            catch (RuntimeException ignored) {}
        }
        if (root.has("titleHudSubtitleVisible")) {
            try { subtitleVisible = root.get("titleHudSubtitleVisible").getAsBoolean(); }
            catch (RuntimeException ignored) {}
        }
        if (root.has("titleHudTitleSize")) {
            try {
                float value = root.get("titleHudTitleSize").getAsFloat();
                if (Float.isFinite(value) && value >= MIN_SIZE && value <= MAX_SIZE)
                    titleSize = value;
            } catch (RuntimeException ignored) {}
        }
        if (root.has("titleHudSubtitleSize")) {
            try {
                float value = root.get("titleHudSubtitleSize").getAsFloat();
                if (Float.isFinite(value) && value >= MIN_SIZE && value <= MAX_SIZE)
                    subtitleSize = value;
            } catch (RuntimeException ignored) {}
        }
        titleOffsetX = readInt(root, "titleHudTitleOffsetX", titleOffsetX);
        titleOffsetY = readInt(root, "titleHudTitleOffsetY", titleOffsetY);
        subtitleOffsetX = readInt(root, "titleHudSubtitleOffsetX", subtitleOffsetX);
        subtitleOffsetY = readInt(root, "titleHudSubtitleOffsetY", subtitleOffsetY);
    }

    private static int readInt(JsonObject root, String key, int fallback) {
        if (!root.has(key)) return fallback;
        try { return root.get(key).getAsInt(); }
        catch (RuntimeException ignored) { return fallback; }
    }
}
