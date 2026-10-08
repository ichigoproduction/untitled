package untitled.untitled.client;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class SyringeCooldownHud {
    private static final Pattern REMAINING_SECONDS = Pattern.compile(
            "남은\\s*시간\\s*:\\s*(\\d+)\\s*초"
    );
    private static final long SECOND_NANOS = 1_000_000_000L;
    private static final long DUPLICATE_WINDOW_NANOS = 300_000_000L;
    private static final int COOLDOWN_SECONDS = 60;
    private static final int DEFAULT_OFFSET_Y = 44;
    private static final int SCREEN_MARGIN = 3;
    private static final float MIN_SCALE = 0.5F;
    private static final float MAX_SCALE = 5.0F;

    private static boolean initialized = false;
    private static boolean enabled = true;
    private static float scale = 1.0F;
    private static int offsetX = 0;
    private static int offsetY = 0;

    private static long expiresAtNanos = 0L;
    private static long lastMessageNanos = 0L;
    private static String lastMessageKey = "";
    private static ClientPlayNetworkHandler lastNetworkHandler = null;
    private static EditHud.HudBounds lastEditorBounds = new EditHud.HudBounds(0, 0, 1, 1);

    private SyringeCooldownHud() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, timestamp) -> onMessage(message.getString())
        );
        ClientReceiveMessageEvents.GAME.register(
                (message, overlay) -> onMessage(message.getString())
        );
        ClientTickEvents.END_CLIENT_TICK.register(SyringeCooldownHud::tickConnection);
        HudRenderCallback.EVENT.register((context, tickCounter) -> renderHud(context));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("ss")
                        .then(literal("toggle").executes(context -> {
                            enabled = !enabled;
                            EditHud.saveSettings();
                            return 1;
                        }))
                        .then(literal("size")
                                .then(argument("scale", FloatArgumentType.floatArg(MIN_SCALE, MAX_SCALE))
                                        .executes(context -> {
                                            scale = FloatArgumentType.getFloat(context, "scale");
                                            clampOffsetsToScreen();
                                            EditHud.saveSettings();
                                            return 1;
                                        })))
                )
        );
    }

    private static void tickConnection(MinecraftClient client) {
        ClientPlayNetworkHandler current = client.getNetworkHandler();
        if (current != lastNetworkHandler) {
            // The first observed connection is not a disconnect: a healing
            // message may already have arrived before this first client tick.
            if (lastNetworkHandler != null) {
                expiresAtNanos = 0L;
                lastMessageNanos = 0L;
                lastMessageKey = "";
            }
            lastNetworkHandler = current;
        }
    }

    private static void onMessage(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (message == null || client.player == null || client.getNetworkHandler() == null) {
            return;
        }

        String clean = message.replace('\u00A0', ' ').trim();
        int seconds;
        String key;

        if (clean.contains("체력을 회복하였습니다.")) {
            seconds = COOLDOWN_SECONDS;
            key = "heal";
        } else if (clean.contains("쿨타임 중입니다.")) {
            Matcher matcher = REMAINING_SECONDS.matcher(clean);
            if (!matcher.find()) {
                return;
            }
            try {
                seconds = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return;
            }
            key = "cooldown:" + seconds;
        } else {
            return;
        }

        long now = System.nanoTime();
        if (key.equals(lastMessageKey) && now - lastMessageNanos < DUPLICATE_WINDOW_NANOS) {
            return;
        }
        lastMessageKey = key;
        lastMessageNanos = now;
        expiresAtNanos = seconds <= 0 ? 0L : now + seconds * SECOND_NANOS;
    }

    private static int remainingSeconds() {
        long remaining = expiresAtNanos - System.nanoTime();
        if (expiresAtNanos == 0L || remaining <= 0L) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (remaining + SECOND_NANOS - 1L) / SECOND_NANOS);
    }

    private static void renderHud(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null
                || client.options.hudHidden || client.currentScreen instanceof EditHud
                || !enabled) {
            return;
        }

        int seconds = remainingSeconds();
        if (seconds > 0) {
            renderText(context, seconds + "s");
        }
    }

    static void renderEditorPreview(DrawContext context) {
        lastEditorBounds = renderText(context, "60s");
    }

    static EditHud.HudBounds getEditorBounds() {
        return lastEditorBounds;
    }

    static void moveBy(int deltaX, int deltaY) {
        offsetX += deltaX;
        offsetY += deltaY;
        clampOffsetsToScreen();
    }

    static void resetPosition() {
        offsetX = 0;
        offsetY = 0;
    }

    static void writeSettings(JsonObject root) {
        root.addProperty("syringeHudEnabled", enabled);
        root.addProperty("syringeHudScale", scale);
        root.addProperty("syringeHudOffsetX", offsetX);
        root.addProperty("syringeHudOffsetY", offsetY);
    }

    static void readSettings(JsonObject root) {
        if (root.has("syringeHudEnabled")) {
            enabled = root.get("syringeHudEnabled").getAsBoolean();
        }
        if (root.has("syringeHudScale")) {
            float saved = root.get("syringeHudScale").getAsFloat();
            if (Float.isFinite(saved)) {
                scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, saved));
            }
        }
        offsetX = root.has("syringeHudOffsetX") ? root.get("syringeHudOffsetX").getAsInt() : 0;
        offsetY = root.has("syringeHudOffsetY") ? root.get("syringeHudOffsetY").getAsInt() : 0;
        clampOffsetsToScreen();
    }

    private static EditHud.HudBounds renderText(DrawContext context, String label) {
        MinecraftClient client = MinecraftClient.getInstance();
        EditHud.HudBounds bounds = layout(client, label);

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(bounds.x(), bounds.y(), 0.0F);
        matrices.scale(scale, scale, 1.0F);

        int cursorX = 0;
        for (int index = 0; index < label.length(); index++) {
            String glyph = label.substring(index, index + 1);
            int shade = label.length() == 1
                    ? 255 : 255 - (100 * index / (label.length() - 1));
            int color = 0xFF000000 | (shade << 16) | (shade << 8) | shade;
            context.drawTextWithShadow(client.textRenderer, glyph, cursorX, 0, color);
            cursorX += client.textRenderer.getWidth(glyph);
        }
        matrices.pop();
        return bounds;
    }

    private static EditHud.HudBounds layout(MinecraftClient client, String label) {
        if (client == null) {
            return new EditHud.HudBounds(0, 0, 1, 1);
        }

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();
        int width = Math.max(1, (int) Math.ceil(client.textRenderer.getWidth(label) * scale));
        int height = Math.max(1, (int) Math.ceil(client.textRenderer.fontHeight * scale));

        int x = screenWidth / 2 - width / 2 + offsetX;
        int y = screenHeight / 2 + DEFAULT_OFFSET_Y + offsetY;
        x = Math.max(SCREEN_MARGIN, Math.min(x, Math.max(SCREEN_MARGIN, screenWidth - width - SCREEN_MARGIN)));
        y = Math.max(SCREEN_MARGIN, Math.min(y, Math.max(SCREEN_MARGIN, screenHeight - height - SCREEN_MARGIN)));
        return new EditHud.HudBounds(x, y, width, height);
    }

    private static void clampOffsetsToScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        EditHud.HudBounds bounds = layout(client, "60s");
        int baseX = client.getWindow().getScaledWidth() / 2 - bounds.width() / 2;
        int baseY = client.getWindow().getScaledHeight() / 2 + DEFAULT_OFFSET_Y;
        offsetX = bounds.x() - baseX;
        offsetY = bounds.y() - baseY;
    }
}
