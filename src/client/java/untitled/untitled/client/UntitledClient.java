package untitled.untitled.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UntitledClient implements ClientModInitializer {
    private static final String QUERY_COMMAND = "thdwjsth";
    private static final String POWER_ICON = "\uE433";

    private static final long QUERY_INTERVAL_MS = 60_000L;
    private static final long RESPONSE_HIDE_WINDOW_MS = 3_000L;

    private static final Pattern REMAIN_PATTERN = Pattern.compile("\\[\\s*남은시간\\s*:\\s*([^\\]]+)\\]");
    private static final Pattern HOURS_PATTERN = Pattern.compile("(\\d+)\\s*시간");
    private static final Pattern MINUTES_PATTERN = Pattern.compile("(\\d+)\\s*분");
    private static final Pattern SECONDS_PATTERN = Pattern.compile("(\\d+)\\s*초");
    private static final Pattern COLON_PATTERN = Pattern.compile("(\\d+):(\\d{2})(?::(\\d{2}))?");

    private static boolean visible = true;
    private static boolean normalPower = false;
    private static boolean surveillance = false;

    private static long endMs = 0L;
    private static long nextQueryMs = 0L;
    private static long hideAutoResponseUntilMs = 0L;

    @Override
    public void onInitializeClient() {
        registerCommand();

        ClientReceiveMessageEvents.ALLOW_CHAT.register(
                (message, signedMessage, sender, params, timestamp) -> handleIncoming(message)
        );
        ClientReceiveMessageEvents.ALLOW_GAME.register(
                (message, overlay) -> handleIncoming(message)
        );

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            endMs = 0L;
            normalPower = false;
            surveillance = false;
            nextQueryMs = System.currentTimeMillis() + 1_000L;
        });

        ClientTickEvents.END_CLIENT_TICK.register(UntitledClient::tick);
        HudRenderCallback.EVENT.register((context, tickCounter) -> renderHud(context));
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

                                            MinecraftClient client = MinecraftClient.getInstance();
                                            if (client.player != null) {
                                                client.player.sendMessage(
                                                        Text.literal("[untitled] 송전소 HUD: " + (visible ? "켜짐" : "꺼짐")),
                                                        false
                                                );
                                            }

                                            return 1;
                                        }))
                )
        );
    }

    private static void tick(MinecraftClient client) {
        if (!visible || client.player == null || client.world == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (nextQueryMs == 0L || now >= nextQueryMs) {
            client.player.networkHandler.sendChatCommand(QUERY_COMMAND);
            nextQueryMs = now + QUERY_INTERVAL_MS;
            hideAutoResponseUntilMs = now + RESPONSE_HIDE_WINDOW_MS;
        }
    }

    private static boolean handleIncoming(Text message) {
        if (message == null) {
            return true;
        }

        String raw = message.getString();
        long now = System.currentTimeMillis();
        boolean matched = readTimer(raw, now) || readNormalPower(raw, now);

        if (now <= hideAutoResponseUntilMs && (matched || isLikelyPowerResponse(raw))) {
            return false;
        }

        return true;
    }

    private static boolean readTimer(String raw, long now) {
        Matcher matcher = REMAIN_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return false;
        }

        long durationMs = parseDurationMs(matcher.group(1));
        if (durationMs <= 0L) {
            return false;
        }

        endMs = now + durationMs;
        normalPower = false;
        surveillance = raw.contains("집중 감시중") || raw.contains("집중 감시");
        nextQueryMs = now + QUERY_INTERVAL_MS;
        return true;
    }

    private static boolean readNormalPower(String raw, long now) {
        if (!raw.contains("송전소") || !raw.contains("정상 작동중")) {
            return false;
        }

        endMs = 0L;
        normalPower = true;
        surveillance = false;
        nextQueryMs = now + QUERY_INTERVAL_MS;
        return true;
    }

    private static boolean isLikelyPowerResponse(String raw) {
        return raw.contains("남은시간")
                || raw.contains("송전소")
                || raw.contains("테러시도")
                || raw.contains(QUERY_COMMAND);
    }

    private static void renderHud(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!visible
                || client.player == null
                || client.world == null
                || client.options.hudHidden) {
            return;
        }

        String value;
        if (normalPower) {
            value = "작동";
        } else {
            long remainingMs = Math.max(0L, endMs - System.currentTimeMillis());
            if (remainingMs <= 0L) {
                return;
            }

            value = formatRemaining(remainingMs);
            if (surveillance) {
                value += " 감시";
            }
        }

        Text icon = Text.literal(POWER_ICON).styled(style -> style.withBold(true));
        Text timer = Text.literal(value).styled(style -> style.withBold(true));

        int gap = 6;
        int totalWidth = client.textRenderer.getWidth(icon)
                + gap
                + client.textRenderer.getWidth(timer);
        int x = (client.getWindow().getScaledWidth() - totalWidth) / 2;
        int y = 10;

        context.drawText(client.textRenderer, icon, x, y, 0xFFFFD166, true);
        context.drawText(
                client.textRenderer,
                timer,
                x + client.textRenderer.getWidth(icon) + gap,
                y + 1,
                0xFFFFB703,
                true
        );
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
}
