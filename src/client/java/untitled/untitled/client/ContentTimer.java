package untitled.untitled.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContentTimer {
    private static final String QUERY_COMMAND = "thdwjsth";
    private static final String POWER_ICON = "\uE433";

    private static final long QUERY_INTERVAL_MS = 60_000L;
    private static final long RESPONSE_HIDE_WINDOW_MS = 3_000L;
    private static final long RESPONSE_LINE_EXTENSION_MS = 900L;

    private static final Pattern AREA_PATTERN =
            Pattern.compile("장소\\s*:\\s*송전소\\s*([ABCabc])\\s*구역");
    private static final Pattern HOURS_PATTERN = Pattern.compile("(\\d+)\\s*시간");
    private static final Pattern MINUTES_PATTERN = Pattern.compile("(\\d+)\\s*분");
    private static final Pattern SECONDS_PATTERN = Pattern.compile("(\\d+)\\s*초");
    private static final Pattern COLON_PATTERN = Pattern.compile("(\\d+):(\\d{2})(?::(\\d{2}))?");

    private static final int ICON_COLOR_A = 0xFFFFD166;
    private static final int ICON_COLOR_B = 0xFFF59E0B;
    private static final int TIME_COLOR_A = 0xFFFFB703;
    private static final int TIME_COLOR_B = 0xFFB45309;
    private static final int GLOW_COLOR = 0x55F59E0B;

    private static final int DANGER_ICON_COLOR_A = 0xFFFF7A7A;
    private static final int DANGER_ICON_COLOR_B = 0xFFC62828;
    private static final int DANGER_TIME_COLOR_A = 0xFFFF5252;
    private static final int DANGER_TIME_COLOR_B = 0xFF8E0000;
    private static final int DANGER_GLOW_COLOR = 0x66EF4444;

    private static final int TEXT_GAP = 6;
    private static final int SCREEN_MARGIN = 2;
    private static final Style BOLD_STYLE = Style.EMPTY.withBold(true);

    private static boolean initialized = false;
    private static boolean visible = true;
    private static boolean normalPower = false;
    private static boolean dangerTimer = false;
    private static boolean refreshWhenTimerEnds = false;

    private static long endMs = 0L;
    private static long nextQueryMs = 0L;
    private static long expectingResponseUntilMs = 0L;
    private static String area = "";
    private static int offsetX = 0;
    private static int offsetY = 0;
    private static EditHud.HudBounds lastEditorBounds = new EditHud.HudBounds(0, 0, 1, 1);

    private ContentTimer() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        registerCommand();

        ClientSendMessageEvents.COMMAND.register(command -> {
            if (isPowerQueryCommand(command)) {
                beginResponseWindow(System.currentTimeMillis());
            }
        });

        ClientReceiveMessageEvents.ALLOW_CHAT.register(
                (message, signedMessage, sender, params, timestamp) -> handleIncoming(message)
        );
        ClientReceiveMessageEvents.ALLOW_GAME.register(
                (message, overlay) -> handleIncoming(message)
        );

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetForJoin());
        ClientTickEvents.END_CLIENT_TICK.register(ContentTimer::tick);
        HudRenderCallback.EVENT.register((context, tickCounter) -> renderHud(context));
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
        lastEditorBounds = renderValue(context, "12:34 [A]", false);
    }

    private static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        ClientCommandManager.literal("content")
                                .then(ClientCommandManager.literal("toggle")
                                        .executes(context -> {
                                            visible = !visible;
                                            if (visible) {
                                                nextQueryMs = 0L;
                                            }
                                            return 1;
                                        }))
                )
        );
    }

    private static void resetForJoin() {
        normalPower = false;
        dangerTimer = false;
        refreshWhenTimerEnds = false;
        endMs = 0L;
        area = "";
        expectingResponseUntilMs = 0L;
        nextQueryMs = System.currentTimeMillis() + 1_000L;
    }

    private static void tick(MinecraftClient client) {
        if (!visible || client.player == null || client.world == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (refreshWhenTimerEnds && endMs > 0L && now >= endMs) {
            refreshWhenTimerEnds = false;
            endMs = 0L;
            nextQueryMs = 0L;
        }

        if (nextQueryMs == 0L || now >= nextQueryMs) {
            beginResponseWindow(now);
            client.player.networkHandler.sendChatCommand(QUERY_COMMAND);
            nextQueryMs = now + QUERY_INTERVAL_MS;
        }
    }

    private static void beginResponseWindow(long now) {
        expectingResponseUntilMs = now + RESPONSE_HIDE_WINDOW_MS;
        area = "";
    }

    private static boolean isPowerQueryCommand(String command) {
        if (command == null) {
            return false;
        }

        String normalized = command.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int space = normalized.indexOf(' ');
        if (space >= 0) {
            normalized = normalized.substring(0, space);
        }
        return QUERY_COMMAND.equalsIgnoreCase(normalized);
    }

    private static boolean handleIncoming(Text message) {
        if (message == null) {
            return true;
        }

        String raw = message.getString();
        long now = System.currentTimeMillis();
        boolean matched = readPowerLine(raw, now);

        if (now <= expectingResponseUntilMs && (matched || isLikelyPowerResponseLine(raw))) {
            expectingResponseUntilMs = Math.max(
                    expectingResponseUntilMs,
                    now + RESPONSE_LINE_EXTENSION_MS
            );
            return false;
        }
        return true;
    }

    private static boolean readPowerLine(String raw, long now) {
        boolean matched = false;

        Matcher areaMatcher = AREA_PATTERN.matcher(raw);
        if (areaMatcher.find()) {
            area = areaMatcher.group(1).toUpperCase(Locale.ROOT);
            matched = true;
        }

        if (raw.contains("현재 송전소가 정상 작동중입니다")
                || (raw.contains("송전소") && raw.contains("정상 작동중"))) {
            normalPower = true;
            dangerTimer = false;
            refreshWhenTimerEnds = false;
            endMs = 0L;
            matched = true;
        }

        if (raw.contains("송전소에 테러가 예고되었습니다")) {
            normalPower = false;
            dangerTimer = false;
            refreshWhenTimerEnds = false;
            endMs = 0L;
            matched = true;
        }

        if (raw.contains("현재 송전소에서 폭탄전이 진행중입니다")) {
            normalPower = false;
            dangerTimer = true;
            refreshWhenTimerEnds = false;
            endMs = 0L;
            matched = true;
        }

        if (raw.contains("현재 송전소가 테러로 인해 가동 중단된 상태입니다")) {
            normalPower = false;
            dangerTimer = false;
            refreshWhenTimerEnds = false;
            endMs = 0L;
            matched = true;
        }

        if (isPowerTimerLine(raw)) {
            long durationMs = parseDurationMs(raw);
            if (durationMs > 0L) {
                normalPower = false;
                dangerTimer = raw.contains("폭파까지 남은시간");
                endMs = now + durationMs;
                refreshWhenTimerEnds = true;
                nextQueryMs = now + QUERY_INTERVAL_MS;
                matched = true;
            }
        }

        return matched;
    }

    private static boolean isPowerTimerLine(String raw) {
        return raw.contains("테러가 예고되어있습니다")
                || raw.contains("폭파까지 남은시간")
                || raw.contains("수리 진행중")
                || raw.contains("[남은시간");
    }

    private static boolean isLikelyPowerResponseLine(String raw) {
        return raw.trim().isEmpty()
                || raw.contains("송전소")
                || raw.contains("남은시간")
                || raw.contains("테러")
                || raw.contains("폭파")
                || raw.contains("장소:")
                || raw.contains("기여도:")
                || raw.contains("수리 진행중")
                || raw.contains("주요시설")
                || raw.contains("보안")
                || raw.contains("자세한 정보")
                || raw.contains("도움말")
                || raw.contains("/송전소")
                || raw.contains("/thdwjsth")
                || raw.contains(QUERY_COMMAND)
                || isPrivateUseDecorationLine(raw);
    }

    private static boolean isPrivateUseDecorationLine(String raw) {
        boolean sawPrivateUse = false;
        for (int offset = 0; offset < raw.length();) {
            int codePoint = raw.codePointAt(offset);
            if (Character.isWhitespace(codePoint)) {
                offset += Character.charCount(codePoint);
                continue;
            }
            if (isPrivateUse(codePoint)) {
                sawPrivateUse = true;
                offset += Character.charCount(codePoint);
                continue;
            }
            return false;
        }
        return sawPrivateUse;
    }

    private static boolean isPrivateUse(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
    }

    private static void renderHud(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!visible
                || client.currentScreen instanceof EditHud
                || client.player == null
                || client.world == null
                || client.options.hudHidden) {
            return;
        }

        String value;
        long remainingMs = Math.max(0L, endMs - System.currentTimeMillis());
        if (remainingMs > 0L) {
            value = formatRemaining(remainingMs);
        } else if (normalPower) {
            value = "작동";
        } else {
            return;
        }

        if (!area.isBlank()) {
            value += " [" + area + "]";
        }
        renderValue(context, value, dangerTimer && remainingMs > 0L);
    }

    private static EditHud.HudBounds renderValue(
            DrawContext context,
            String value,
            boolean danger
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        int iconWidth = client.textRenderer.getWidth(POWER_ICON);
        int totalWidth = iconWidth + TEXT_GAP + client.textRenderer.getWidth(value);
        int totalHeight = client.textRenderer.fontHeight + 3;
        int screenWidth = context.getScaledWindowWidth();
        int screenHeight = context.getScaledWindowHeight();
        int x = (screenWidth - totalWidth) / 2 + offsetX;
        int y = 10 + offsetY;
        x = clamp(x, SCREEN_MARGIN, Math.max(SCREEN_MARGIN, screenWidth - totalWidth - SCREEN_MARGIN));
        y = clamp(y, SCREEN_MARGIN, Math.max(SCREEN_MARGIN, screenHeight - totalHeight - SCREEN_MARGIN));

        drawGradientLine(context, client, x, y, value, danger);
        return new EditHud.HudBounds(x, y, totalWidth, totalHeight);
    }

    private static void drawGradientLine(
            DrawContext context,
            MinecraftClient client,
            int x,
            int y,
            String value,
            boolean danger
    ) {
        int iconWidth = client.textRenderer.getWidth(POWER_ICON);
        int valueX = x + iconWidth + TEXT_GAP;

        int iconColorA = danger ? DANGER_ICON_COLOR_A : ICON_COLOR_A;
        int iconColorB = danger ? DANGER_ICON_COLOR_B : ICON_COLOR_B;
        int timeColorA = danger ? DANGER_TIME_COLOR_A : TIME_COLOR_A;
        int timeColorB = danger ? DANGER_TIME_COLOR_B : TIME_COLOR_B;
        int glowColor = danger ? DANGER_GLOW_COLOR : GLOW_COLOR;

        drawGradientString(context, client, POWER_ICON, x + 1, y + 1,
                iconColorA, iconColorB, glowColor);
        drawGradientString(context, client, value, valueX + 1, y + 2,
                timeColorA, timeColorB, glowColor);
        drawGradientString(context, client, POWER_ICON, x, y,
                iconColorA, iconColorB, 0);
        drawGradientString(context, client, value, valueX, y + 1,
                timeColorA, timeColorB, 0);
    }

    private static void drawGradientString(
            DrawContext context,
            MinecraftClient client,
            String value,
            int x,
            int y,
            int colorA,
            int colorB,
            int overrideAlphaColor
    ) {
        if (value == null || value.isEmpty()) {
            return;
        }

        int currentX = x;
        int characterCount = value.length();
        for (int index = 0; index < characterCount; index++) {
            String character = value.substring(index, index + 1);
            float progress = characterCount <= 1
                    ? 0.0F
                    : index / (float) (characterCount - 1);
            int color = lerpArgb(colorA, colorB, progress);
            if (overrideAlphaColor != 0) {
                int alpha = (overrideAlphaColor >>> 24) & 0xFF;
                color = (alpha << 24) | (color & 0x00FFFFFF);
            }

            Text text = Text.literal(character).setStyle(BOLD_STYLE);
            context.drawText(client.textRenderer, text, currentX, y, color, false);
            currentX += client.textRenderer.getWidth(text);
        }
    }

    private static int lerpArgb(int colorA, int colorB, float progress) {
        progress = Math.max(0.0F, Math.min(1.0F, progress));
        int alphaA = (colorA >>> 24) & 0xFF;
        int redA = (colorA >>> 16) & 0xFF;
        int greenA = (colorA >>> 8) & 0xFF;
        int blueA = colorA & 0xFF;
        int alphaB = (colorB >>> 24) & 0xFF;
        int redB = (colorB >>> 16) & 0xFF;
        int greenB = (colorB >>> 8) & 0xFF;
        int blueB = colorB & 0xFF;
        int alpha = (int) (alphaA + (alphaB - alphaA) * progress);
        int red = (int) (redA + (redB - redA) * progress);
        int green = (int) (greenA + (greenB - greenA) * progress);
        int blue = (int) (blueA + (blueB - blueA) * progress);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static String formatRemaining(long milliseconds) {
        long totalSeconds = Math.max(0L, (milliseconds + 999L) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private static long parseDurationMs(String text) {
        long seconds = 0L;
        seconds += matchUnit(text, HOURS_PATTERN) * 3600L;
        seconds += matchUnit(text, MINUTES_PATTERN) * 60L;
        seconds += matchUnit(text, SECONDS_PATTERN);
        if (seconds > 0L) {
            return seconds * 1000L;
        }

        Matcher matcher = COLON_PATTERN.matcher(text);
        if (!matcher.find()) {
            return 0L;
        }
        long first = parseLong(matcher.group(1));
        long second = parseLong(matcher.group(2));
        String third = matcher.group(3);
        if (third == null) {
            return (first * 60L + second) * 1000L;
        }
        return (first * 3600L + second * 60L + parseLong(third)) * 1000L;
    }

    private static long matchUnit(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? parseLong(matcher.group(1)) : 0L;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
