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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Client-side, data-driven skill cooldown HUD. */
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
    // Observed 0.65s correction was ~0.25s late; 0.90s is a trial value.
    private static final long SERVER_READY_CORRECTION = 900_000_000L;
    // Receiving the same packet through packet/HUD/Fabric hooks must not
    // process one cast multiple times (distinct rapid casts are unaffected).
    private static final long DUPLICATE_WINDOW = 150_000_000L;
    private static final long RECAST_GRACE = 750_000_000L;
    private static final int GAP = 3;
    private static final int MARGIN = 3;
    private static final int DEFAULT_Y = 80;
    private static final Map<String, Skill> CATALOG_SKILLS = new LinkedHashMap<>();
    private static final Map<String, Boolean> VISIBLE = new LinkedHashMap<>();
    private static final Map<String, Long> EXPIRY = new HashMap<>();

    private static boolean initialized;
    private static boolean enabled = true;
    private static float scale = 1.0f;
    private static int offsetX, offsetY;
    private static ClientPlayNetworkHandler lastConnection;
    private static String lastActionbar = "";
    private static long lastActionbarNano;
    private static EditHud.HudBounds editorBounds = new EditHud.HudBounds(0, 0, 1, 1);

    private SkillHud() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        loadCatalog();

        HudRenderCallback.EVENT.register((context, ticks) -> renderHud(context));
        ClientTickEvents.END_CLIENT_TICK.register(SkillHud::tickConnection);
        // Some servers/mods route overlay text through Fabric GAME callbacks
        // instead of, or in addition to, the dedicated HUD overlay setter.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) onHudText(message);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            var root = literal("sh")
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
                                "등록된 스킬: " + CATALOG_SKILLS.size()));
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

    /** Receives the actionbar text through the HUD mixin, including dedicated overlay packets. */
    public static void onHudText(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (text == null || client.player == null || client.world == null) return;
        String content = normalizeActionbar(text.getString());
        if (content.isEmpty()) return;
        long now = System.nanoTime();
        // The same overlay is observable at three different interception
        // points. Do not mistake these duplicate callbacks for separate casts.
        if (content.equals(lastActionbar)
                && now - lastActionbarNano < DUPLICATE_WINDOW) return;
        lastActionbar = content;
        lastActionbarNano = now;
        onActionbar(content);
    }

    /**
     * A resource pack can insert invisible formatting and private-use glyphs
     * between Hangul characters. Strip these for recognition; keep actual
     * Korean text and ASCII digits so trigger names remain detectable.
     */
    private static String normalizeActionbar(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            int kind = Character.getType(cp);
            if (kind != Character.PRIVATE_USE && kind != Character.FORMAT
                    && !Character.isISOControl(cp)) {
                out.appendCodePoint(cp == 0xA0 ? ' ' : cp);
            }
        });
        return Normalizer.normalize(out, Normalizer.Form.NFC).trim();
    }

    private static void onActionbar(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        // One packet can contain more than one skill name. Group definitions
        // by trigger and process each distinct trigger independently.
        // Treat spacing between Hangul syllables as decorative for matching.
        String skillText = message.replaceAll("\\s+", "");
        Map<String, List<Skill>> byTrigger = new LinkedHashMap<>();
        for (Skill skill : CATALOG_SKILLS.values()) {
            if (skillText.contains(skill.trigger())) {
                byTrigger.computeIfAbsent(skill.trigger(), ignored -> new ArrayList<>())
                        .add(skill);
            }
        }
        if (byTrigger.isEmpty()) return;

        String held = client.player.getMainHandStack().getName().getString();
        long now = System.nanoTime();
        int distinctTriggers = 0;
        for (String trigger : byTrigger.keySet()) {
            if (hasIndependentOccurrence(skillText, trigger, byTrigger)) {
                distinctTriggers++;
            }
        }

        for (Map.Entry<String, List<Skill>> entry : byTrigger.entrySet()) {
            if (!hasIndependentOccurrence(skillText, entry.getKey(), byTrigger)) continue;
            Skill match = chooseSkill(entry.getValue(), held);
            if (match == null) continue;

            // Only use remaining seconds that immediately follow the named
            // skill, not a number belonging to another HUD element or skill.
            Integer serverSeconds = distinctTriggers == 1
                    ? readSkillSeconds(message, match) : null;

            if (serverSeconds != null) {
                // A skill-specific countdown from the server has priority.
                setExpiry(match, now, serverSeconds);
            } else if (!isCooldownRejection(message)
                    && EXPIRY.getOrDefault(match.key(), 0L) <= now + RECAST_GRACE) {
                // A genuine recast can arrive just before our local clock
                // expires; the server may already have accepted it. Re-arm
                // near expiry, but ignore repeated success packets during
                // the main cooldown window.
                setExpiry(match, now, match.cooldown());
            }
        }
    }

    /**
     * Ignore unrelated numbers elsewhere in actionbar text. Only a positive
     * countdown close to the named skill can correct its local deadline.
     */
    private static Integer readSkillSeconds(String message, Skill skill) {
        int start = message.indexOf(skill.trigger());
        if (start < 0) return null;
        int from = start + skill.trigger().length();
        String suffix = message.substring(from, Math.min(message.length(), from + 32));
        Matcher match = SECONDS.matcher(suffix);
        if (!match.find() || match.start() > 16) return null;
        try {
            int seconds = Integer.parseInt(match.group(1));
            return seconds > 0 && seconds <= skill.cooldown() ? seconds : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** A cooldown rejection without a numeric remainder is not a cast. */
    private static boolean isCooldownRejection(String message) {
        return message.contains("쿨타임 중")
                || message.contains("쿨타임이 남")
                || message.contains("재사용 대기")
                || message.contains("사용할 수 없")
                || message.contains("아직 사용할 수 없");
    }

    /**
     * A shorter trigger occurring inside a longer skill name is not an
     * independent cast. A separate occurrence elsewhere in the same message
     * is still counted, allowing consecutive or combined skill events.
     */
    private static boolean hasIndependentOccurrence(
            String message, String trigger, Map<String, List<Skill>> triggers
    ) {
        for (int index = message.indexOf(trigger); index >= 0;
                index = message.indexOf(trigger, index + 1)) {
            boolean covered = false;
            for (String longer : triggers.keySet()) {
                if (longer.length() <= trigger.length()) continue;
                for (int otherIndex = message.indexOf(longer); otherIndex >= 0;
                        otherIndex = message.indexOf(longer, otherIndex + 1)) {
                    if (otherIndex <= index
                            && index + trigger.length() <= otherIndex + longer.length()) {
                        covered = true;
                        break;
                    }
                }
                if (covered) break;
            }
            if (!covered) return true;
        }
        return false;
    }

    /** Disambiguate shared trigger names using the held item when possible. */
    private static Skill chooseSkill(List<Skill> candidates, String held) {
        Skill exact = null;
        Skill partial = null;
        int exactCount = 0;
        int partialCount = 0;
        for (Skill skill : candidates) {
            if (skill.sword().equals("global") || skill.itemName().equals(held)) {
                exact = skill;
                exactCount++;
            } else if (!held.isEmpty() && held.contains(skill.itemName())) {
                partial = skill;
                partialCount++;
            }
        }
        if (exactCount == 1) return exact;
        if (exactCount > 1) return null;
        if (partialCount == 1) return partial;
        if (partialCount > 1) return null;
        // A unique trigger is reliable even when server plugins decorate
        // the item name. Ambiguous names must not start the wrong timer.
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static void setExpiry(Skill skill, long now, int seconds) {
        // A full 1s adjustment was observed to end one second too early;
        // start with the midpoint (0.5s) as a provisional server alignment.
        long correctedDuration = Math.max(0L,
                seconds * SECOND - SERVER_READY_CORRECTION);
        if (correctedDuration == 0L) {
            EXPIRY.remove(skill.key());
        } else {
            EXPIRY.put(skill.key(), now + correctedDuration);
        }
    }

    private static void tickConnection(MinecraftClient client) {
        ClientPlayNetworkHandler current = client.getNetworkHandler();
        if (current != lastConnection) {
            if (lastConnection != null) {
                EXPIRY.clear();
                lastActionbar = "";
                lastActionbarNano = 0L;
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
