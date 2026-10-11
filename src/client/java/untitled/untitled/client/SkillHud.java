package untitled.untitled.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-side, data-driven skill cooldown HUD. Server-specific catalog ships empty. */
public final class SkillHud {
    private record Skill(String sword, String slot, String name, String itemName,
                         String trigger, int cooldown) {
        String key() { return sword + "|" + slot; }
    }

    private static final Logger LOG = LoggerFactory.getLogger("untitled/SkillHud");
    private static final String CATALOG = "/assets/untitled/skill_catalog.json";
    private static final String[] WEAPON_SLOTS = {"l", "sl", "sr", "ss"};
    private static final String[] ALL_SLOTS = {"l", "r", "sl", "sr", "ss"};
    private static final Pattern SECONDS =
            Pattern.compile("(?<!\\d)(\\d{1,4})\\s*(?:초|s)(?![a-zA-Z])");
    private static final long SECOND = 1_000_000_000L;
    private static final int GAP = 3;
    private static final int MARGIN = 3;
    private static final int DEFAULT_Y = 80;
    private static final Map<String, Skill> CATALOG_SKILLS = new LinkedHashMap<>();
    private static final Map<String, Boolean> VISIBLE = new LinkedHashMap<>();
    private static final Map<String, Long> EXPIRY = new HashMap<>();

    private static boolean initialized, debug;
    private static boolean enabled = true;
    private static float scale = 1.0f;
    private static int offsetX, offsetY;
    private static ClientPlayNetworkHandler lastConnection;
    private static String lastDebugText = "";
    private static long lastDebugTime;
    private static EditHud.HudBounds editorBounds = new EditHud.HudBounds(0, 0, 1, 1);

    private SkillHud() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        loadCatalog();

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) onActionbar(message.getString());
        });
        HudRenderCallback.EVENT.register((context, ticks) -> renderHud(context));
        ClientTickEvents.END_CLIENT_TICK.register(SkillHud::tickConnection);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            var root = literal("sh")
                    .then(literal("debug").executes(ctx -> {
                        debug = !debug;
                        lastDebugText = "";
                        ctx.getSource().sendFeedback(Text.literal(
                                "[SH] actionbar debug " + (debug ? "ON" : "OFF")
                                        + " | logs/latest.log"));
                        return 1;
                    }))
                    .then(literal("toggle").executes(ctx -> {
                        enabled = !enabled;
                        EditHud.saveSettings();
                        return 1;
                    }))
                    .then(literal("size")
                            .then(argument("scale", FloatArgumentType.floatArg(0.5f, 5.0f))
                                    .executes(ctx -> {
                                        scale = FloatArgumentType.getFloat(ctx, "scale");
                                        clampOffsets();
                                        EditHud.saveSettings();
                                        return 1;
                                    })))
                    .then(literal("list").executes(ctx -> {
                        ctx.getSource().sendFeedback(Text.literal(
                                "[SH] registered skills: " + CATALOG_SKILLS.size()));
                        for (Skill s : CATALOG_SKILLS.values()) {
                            ctx.getSource().sendFeedback(Text.literal(
                                    s.sword() + " " + s.slot() + " " + s.name()
                                            + " " + s.cooldown() + "s | "
                                            + (isVisible(s.key()) ? "ON" : "OFF")));
                        }
                        return 1;
                    }))
                    .then(literal("reset").executes(ctx -> {
                        VISIBLE.clear();
                        EditHud.saveSettings();
                        return 1;
                    }));
            var swordArg = argument("sword", StringArgumentType.word())
                    .then(literal("all")
                            .then(literal("toggle").executes(ctx ->
                                    toggleAll(StringArgumentType.getString(ctx, "sword")))));
            for (String slot : ALL_SLOTS) {
                swordArg.then(literal(slot)
                        .then(literal("toggle").executes(ctx ->
                                toggleSlot(StringArgumentType.getString(ctx, "sword"), slot))));
            }
            root.then(swordArg);
            dispatcher.register(root);
        });
    }

    private static String key(String sword, String slot) {
        // Right click is the shared leap across swords.
        return slot.equals("r") ? "global|r" : sword + "|" + slot;
    }

    private static boolean isVisible(String key) {
        return VISIBLE.getOrDefault(key, false);
    }

    private static int toggleSlot(String sword, String slot) {
        if (sword.equals("global") && !slot.equals("r")) return 0;
        String key = key(sword, slot);
        VISIBLE.put(key, !isVisible(key));
        EditHud.saveSettings();
        return 1;
    }

    private static int toggleAll(String sword) {
        if (sword.equals("global")) return toggleSlot("global", "r");
        boolean allOn = true;
        for (String slot : WEAPON_SLOTS) {
            if (!isVisible(key(sword, slot))) allOn = false;
        }
        for (String slot : WEAPON_SLOTS) {
            VISIBLE.put(key(sword, slot), !allOn);
        }
        EditHud.saveSettings();
        return 1;
    }

    private static String readString(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonPrimitive()
                ? object.get(field).getAsString().trim() : "";
    }

    private static void addSkill(String sword, String slot, String item, JsonObject data) {
        try {
            String name = readString(data, "name");
            String trigger = readString(data, "trigger");
            int seconds = data.get("cooldown").getAsInt();
            if (name.isEmpty() || trigger.isEmpty() || seconds < 1 || seconds > 3600) {
                LOG.warn("Invalid skill mapping {} {}", sword, slot);
                return;
            }
            Skill skill = new Skill(sword, slot, name, item, trigger, seconds);
            CATALOG_SKILLS.put(skill.key(), skill);
        } catch (Exception error) {
            LOG.warn("Invalid skill mapping {} {}", sword, slot);
        }
    }

    private static void loadCatalog() {
        CATALOG_SKILLS.clear();
        try (InputStream input = SkillHud.class.getResourceAsStream(CATALOG)) {
            if (input == null) {
                LOG.warn("Missing skill catalog {}", CATALOG);
                return;
            }
            JsonElement element = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) return;
            JsonObject root = element.getAsJsonObject();
            if (root.has("global") && root.get("global").isJsonObject()) {
                JsonObject common = root.getAsJsonObject("global");
                if (common.has("r") && common.get("r").isJsonObject()) {
                    addSkill("global", "r", "", common.getAsJsonObject("r"));
                }
            }
            if (root.has("swords") && root.get("swords").isJsonArray()) {
                for (JsonElement e : root.getAsJsonArray("swords")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject sword = e.getAsJsonObject();
                    String id = readString(sword, "id");
                    String item = readString(sword, "item_name");
                    if (!id.matches("[a-zA-Z0-9_]+") || id.equals("global")
                            || item.isEmpty() || !sword.has("skills")
                            || !sword.get("skills").isJsonObject()) continue;
                    JsonObject slots = sword.getAsJsonObject("skills");
                    for (String slot : WEAPON_SLOTS) {
                        if (slots.has(slot) && slots.get(slot).isJsonObject()) {
                            addSkill(id, slot, item, slots.getAsJsonObject(slot));
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Cannot load skill catalog", e);
            CATALOG_SKILLS.clear();
        }
        LOG.info("Loaded {} skill definitions", CATALOG_SKILLS.size());
    }

    private static void onActionbar(String actionbar) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;
        String message = actionbar.replace('\u00A0', ' ').trim();
        if (message.isEmpty()) return;
        if (debug) debugMessage(client, message);

        String held = client.player.getMainHandStack().getName().getString();
        Skill match = null;
        for (Skill skill : CATALOG_SKILLS.values()) {
            if (!skill.sword().equals("global") && !skill.itemName().equals(held)) continue;
            if (message.contains(skill.trigger()) && (match == null
                    || skill.trigger().length() > match.trigger().length())) match = skill;
        }
        if (match == null) return;

        long now = System.nanoTime();
        Matcher matcher = SECONDS.matcher(message);
        if (matcher.find()) {
            try {
                int seconds = Integer.parseInt(matcher.group(1));
                if (seconds <= 3600) EXPIRY.put(match.key(), now + seconds * SECOND);
            } catch (NumberFormatException ignored) {}
        } else if (EXPIRY.getOrDefault(match.key(), 0L) <= now) {
            // Repeated success actionbars must not restart an active cooldown.
            EXPIRY.put(match.key(), now + match.cooldown() * SECOND);
        }
    }

    private static void debugMessage(MinecraftClient client, String message) {
        long now = System.nanoTime();
        if (message.equals(lastDebugText) && now - lastDebugTime < 2 * SECOND) return;
        lastDebugText = message;
        lastDebugTime = now;
        String raw = message.length() > 120 ? message.substring(0, 120) + "..." : message;
        StringBuilder encoded = new StringBuilder();
        message.codePoints().limit(120).forEach(cp -> {
            if (cp >= 32 && cp <= 126 && cp != '\\') encoded.appendCodePoint(cp);
            else encoded.append(String.format("\\u{%X}", cp));
        });
        LOG.info("ACTIONBAR raw={} unicode={}", raw, encoded);
        client.player.sendMessage(Text.literal("[SH] raw: " + raw), false);
        String dump = encoded.toString();
        for (int i = 0; i < dump.length(); i += 160) {
            client.player.sendMessage(Text.literal("[SH] unicode: "
                    + dump.substring(i, Math.min(dump.length(), i + 160))), false);
        }
    }

    private static void tickConnection(MinecraftClient client) {
        ClientPlayNetworkHandler current = client.getNetworkHandler();
        if (current != lastConnection) {
            if (lastConnection != null) {
                EXPIRY.clear();
                lastDebugText = "";
            }
            lastConnection = current;
        }
    }

    private static int secondsLeft(Skill skill) {
        long left = EXPIRY.getOrDefault(skill.key(), 0L) - System.nanoTime();
        return left <= 0 ? 0 : (int) ((left + SECOND - 1) / SECOND);
    }

    private static List<String> activeLines() {
        List<String> result = new ArrayList<>();
        for (Skill skill : CATALOG_SKILLS.values()) {
            int seconds = secondsLeft(skill);
            if (isVisible(skill.key()) && seconds > 0) {
                result.add(skill.name() + " " + seconds + "s");
            }
        }
        return result;
    }

    private static void renderHud(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || client.player == null || client.world == null
                || client.options.hudHidden || client.currentScreen instanceof EditHud) return;
        List<String> lines = activeLines();
        if (!lines.isEmpty()) drawLines(context, lines);
    }

    static void renderEditorPreview(DrawContext context) {
        List<String> lines = activeLines();
        editorBounds = drawLines(context,
                lines.isEmpty() ? List.of("SKILL 8s", "SKILL 11s") : lines);
    }

    static EditHud.HudBounds getEditorBounds() { return editorBounds; }

    private static EditHud.HudBounds drawLines(DrawContext context, List<String> lines) {
        MinecraftClient client = MinecraftClient.getInstance();
        int width = 1;
        for (String line : lines) width = Math.max(width, client.textRenderer.getWidth(line));
        int lineHeight = client.textRenderer.fontHeight + GAP;
        int w = Math.max(1, (int) Math.ceil(width * scale));
        int h = Math.max(1, (int) Math.ceil(
                (lines.size() * lineHeight - GAP) * scale));
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int x = Math.max(MARGIN, Math.min(sw / 2 - w / 2 + offsetX,
                Math.max(MARGIN, sw - w - MARGIN)));
        int y = Math.max(MARGIN, Math.min(sh / 2 + DEFAULT_Y + offsetY,
                Math.max(MARGIN, sh - h - MARGIN)));

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x, y, 0.0F);
        matrices.scale(scale, scale, 1.0F);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int split = line.lastIndexOf(' ');
            int py = i * lineHeight;
            if (split < 0) {
                context.drawTextWithShadow(client.textRenderer, line, 0, py, 0xFFFFFFFF);
            } else {
                String name = line.substring(0, split);
                context.drawTextWithShadow(client.textRenderer, name, 0, py, 0xFFFFFFFF);
                context.drawTextWithShadow(client.textRenderer, line.substring(split),
                        client.textRenderer.getWidth(name), py, 0xFFBFC3CB);
            }
        }
        matrices.pop();
        return new EditHud.HudBounds(x, y, w, h);
    }

    static void moveBy(int dx, int dy) {
        offsetX += dx;
        offsetY += dy;
        clampOffsets();
    }

    static void resetPosition() { offsetX = 0; offsetY = 0; }

    private static void clampOffsets() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int w = (int) Math.ceil(client.textRenderer.getWidth("SKILL 11s") * scale);
        int h = (int) Math.ceil((2 * client.textRenderer.fontHeight + GAP) * scale);
        int x = Math.max(MARGIN, Math.min(sw / 2 - w / 2 + offsetX,
                Math.max(MARGIN, sw - w - MARGIN)));
        int y = Math.max(MARGIN, Math.min(sh / 2 + DEFAULT_Y + offsetY,
                Math.max(MARGIN, sh - h - MARGIN)));
        offsetX = x - (sw / 2 - w / 2);
        offsetY = y - (sh / 2 + DEFAULT_Y);
    }

    static void writeSettings(JsonObject root) {
        root.addProperty("skillHudEnabled", enabled);
        root.addProperty("skillHudSize", scale);
        root.addProperty("skillHudOffsetX", offsetX);
        root.addProperty("skillHudOffsetY", offsetY);
        JsonObject visible = new JsonObject();
        VISIBLE.forEach(visible::addProperty);
        root.add("skillHudVisible", visible);
    }

    static void readSettings(JsonObject root) {
        if (root.has("skillHudEnabled")) enabled = root.get("skillHudEnabled").getAsBoolean();
        if (root.has("skillHudSize")) {
            float loaded = root.get("skillHudSize").getAsFloat();
            if (Float.isFinite(loaded)) scale = Math.max(0.5f, Math.min(5.0f, loaded));
        }
        if (root.has("skillHudOffsetX")) offsetX = root.get("skillHudOffsetX").getAsInt();
        if (root.has("skillHudOffsetY")) offsetY = root.get("skillHudOffsetY").getAsInt();
        if (root.has("skillHudVisible") && root.get("skillHudVisible").isJsonObject()) {
            VISIBLE.clear();
            for (Map.Entry<String, JsonElement> entry
                    : root.getAsJsonObject("skillHudVisible").entrySet()) {
                try {
                    VISIBLE.put(entry.getKey(), entry.getValue().getAsBoolean());
                } catch (Exception ignored) {}
            }
        }
    }
}
