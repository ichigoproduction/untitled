package untitled.untitled.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Client-side player outlines with a persistent UUID -> RGB map.
 * No entity glow flags or server state are modified.
 */
public final class Glow {
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("152_glow.json");
    private static final int DEFAULT_COLOR = 0xFFFFFF;
    private static final Map<UUID, Target> targets = new LinkedHashMap<>();
    private static final Map<String, Integer> COLORS = Map.ofEntries(
            Map.entry("white", 0xFFFFFF), Map.entry("black", 0x000000),
            Map.entry("red", 0xFF5555), Map.entry("green", 0x55FF55),
            Map.entry("blue", 0x5555FF), Map.entry("yellow", 0xFFFF55),
            Map.entry("aqua", 0x55FFFF), Map.entry("cyan", 0x00FFFF),
            Map.entry("pink", 0xFF77AA), Map.entry("purple", 0xAA55FF),
            Map.entry("orange", 0xFFAA00), Map.entry("lime", 0x39FF14),
            Map.entry("gray", 0x888888), Map.entry("lightgray", 0xBBBBBB)
    );

    private static boolean enabled = false;
    // The local player's Glow can be disabled independently of registered targets.
    // Defaults to true to preserve behavior of existing 152_glow.json files.
    private static boolean selfEnabled = true;
    private static int selfColor = DEFAULT_COLOR;

    private record Target(String name, int color) {}

    private Glow() {}

    public static void init() {
        load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("glow")
                    .executes(ctx -> {
                        showStatus();
                        return 1;
                    })
                    .then(literal("toggle").executes(ctx -> {
                        enabled = !enabled;
                        save();
                        success(gray("Glow ").append(stateText()));
                        return 1;
                    }))
                    .then(literal("self")
                            .then(literal("toggle").executes(ctx -> {
                                selfEnabled = !selfEnabled;
                                save();
                                success(gray("Self Glow ").append(selfStateText()));
                                return 1;
                            })))
                    .then(literal("color")
                            .then(argument("color", StringArgumentType.word())
                                    .suggests((ctx, builder) -> suggestColors(builder))
                                    .executes(ctx -> {
                                        Integer value = parseColor(StringArgumentType.getString(ctx, "color"));
                                        if (value == null) {
                                            failure(gray("Unknown color").append(dim(" (RRGGBB or named color)")));
                                            return 0;
                                        }
                                        selfColor = value;
                                        save();
                                        success(gray("My color ").append(dim("→ ")).append(rgbText(selfColor)));
                                        return 1;
                                    })))
                    .then(literal("remove")
                            .then(argument("player", StringArgumentType.word())
                                    .suggests((ctx, builder) -> suggestRegistered(builder))
                                    .executes(ctx -> remove(StringArgumentType.getString(ctx, "player")))))
                    .then(literal("clear").executes(ctx -> {
                        targets.clear();
                        save();
                        success(gray("Registered players ").append(dim("→ ")).append(red("CLEARED")));
                        return 1;
                    }))
                    .then(literal("list").executes(ctx -> {
                        showStatus();
                        for (Target target : targets.values()) {
                            info(whiteBold(target.name()).append(dim(" → ")).append(rgbText(target.color())));
                        }
                        return 1;
                    }))
                    .then(argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> suggestPlayers(builder))
                            .executes(ctx -> addOrUpdate(
                                    StringArgumentType.getString(ctx, "player"), null))
                            .then(argument("color", StringArgumentType.word())
                                    .suggests((ctx, builder) -> suggestColors(builder))
                                    .executes(ctx -> addOrUpdate(
                                            StringArgumentType.getString(ctx, "player"),
                                            StringArgumentType.getString(ctx, "color"))))));
        });
    }


    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestColors(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String name : COLORS.keySet()) {
            if (name.startsWith(prefix)) builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayers(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getNetworkHandler() != null) {
            for (PlayerListEntry e : client.getNetworkHandler().getPlayerList()) {
                String name = e.getProfile().getName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix)) builder.suggest(name);
            }
        }
        for (Target target : targets.values()) {
            if (target.name().toLowerCase(Locale.ROOT).startsWith(prefix)) builder.suggest(target.name());
        }
        return builder.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRegistered(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (Target target : targets.values()) {
            if (target.name().toLowerCase(Locale.ROOT).startsWith(prefix)) builder.suggest(target.name());
        }
        return builder.buildFuture();
    }

    private static int addOrUpdate(String name, String requestedColor) {
        Integer rgb = requestedColor == null ? null : parseColor(requestedColor);
        if (requestedColor != null && rgb == null) {
            failure(gray("Unknown color ").append(whiteBold(requestedColor)));
            return 0;
        }
        UUID uuid = resolveUuid(name);
        if (uuid == null) {
            failure(gray("Player not found").append(dim(" (online or previously registered only)")));
            return 0;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && uuid.equals(client.player.getUuid())) {
            if (rgb != null) {
                selfColor = rgb;
                save();
                success(gray("My color ").append(dim("→ ")).append(rgbText(selfColor)));
            } else {
                info(gray("Set your color with ").append(whiteBold("/glow color <color>")));
            }
            return 1;
        }
        Target previous = targets.get(uuid);
        String canonicalName = name;
        if (client != null && client.getNetworkHandler() != null) {
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(uuid);
            if (entry != null && entry.getProfile() != null) canonicalName = entry.getProfile().getName();
        }
        int assigned = rgb != null ? rgb : (previous == null ? DEFAULT_COLOR : previous.color());
        targets.put(uuid, new Target(canonicalName, assigned));
        save();
        success(gray("Player ").append(whiteBold(canonicalName)).append(dim(" → ")).append(rgbText(assigned)));
        return 1;
    }

    private static int remove(String name) {
        UUID uuid = resolveUuid(name);
        if (uuid == null || targets.remove(uuid) == null) {
            failure(gray("Not registered ").append(whiteBold(name)));
            return 0;
        }
        save();
        success(gray("Removed ").append(whiteBold(name)));
        return 1;
    }

    private static UUID resolveUuid(String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getNetworkHandler() != null) {
            for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
                if (entry.getProfile() != null && name.equalsIgnoreCase(entry.getProfile().getName()))
                    return entry.getProfile().getId();
            }
        }
        if (client != null && client.player != null
                && name.equalsIgnoreCase(client.player.getGameProfile().getName()))
            return client.player.getUuid();
        for (Map.Entry<UUID, Target> entry : targets.entrySet()) {
            if (name.equalsIgnoreCase(entry.getValue().name())) return entry.getKey();
        }
        return null;
    }

    /** A vanilla glowing outline is preserved when the mod is disabled. */
    public static boolean shouldOutline(Entity entity) {
        return colorFor(entity) != null;
    }

    /** Only named player UUIDs get a color override; all other outlines keep vanilla RGB. */
    public static Integer colorFor(Entity entity) {
        if (!enabled || !(entity instanceof PlayerEntity player) || !player.isAlive() || player.isSpectator())
            return null;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return null;
        if (player.getUuid().equals(client.player.getUuid())) {
            return selfEnabled ? selfColor : null;
        }
        Target target = targets.get(player.getUuid());
        return target == null ? null : target.color();
    }

    private static Integer parseColor(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (COLORS.containsKey(s)) return COLORS.get(s);
        if (s.startsWith("#")) s = s.substring(1);
        else if (s.startsWith("0x")) s = s.substring(2);
        if (!s.matches("[0-9a-f]{6}")) return null;
        try { return Integer.parseInt(s, 16); }
        catch (NumberFormatException e) { return null; }
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    // Match Skin/Cape chat styling: pastel gradient [152] and distinct result colors.
    private static MutableText header() {
        return Text.empty()
                .append(tint("[", 0x666666, false))
                .append(gradient("152", 0xFFE4FF, 0xC0FFFF))
                .append(tint("] ", 0x666666, false))
                .append(whiteBold("Glow "))
                .append(tint("• ", 0x777777, false));
    }

    private static void send(MutableText message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null) {
            client.inGameHud.getChatHud().addMessage(header().append(message));
        }
    }

    private static void success(MutableText message) {
        send(green("✓ ").append(message));
    }

    private static void failure(MutableText message) {
        send(red("✖ ").append(message));
    }

    private static void info(MutableText message) {
        send(dim("… ").append(message));
    }

    private static void showStatus() {
        info(gray("Status ").append(stateText())
                .append(dim("  |  Self: ")).append(selfStateText())
                .append(dim("  |  My color: ")).append(rgbText(selfColor))
                .append(dim("  |  Players: ")).append(whiteBold(Integer.toString(targets.size()))));
    }

    private static MutableText stateText() {
        return enabled ? green("ON") : red("OFF");
    }

    private static MutableText selfStateText() {
        return selfEnabled ? green("ON") : red("OFF");
    }

    private static MutableText rgbText(int rgb) {
        return tint(hex(rgb), rgb & 0xFFFFFF, true);
    }

    private static MutableText gray(String value) {
        return tint(value, 0xAAAAAA, false);
    }

    private static MutableText dim(String value) {
        return tint(value, 0x777777, false);
    }

    private static MutableText whiteBold(String value) {
        return tint(value, 0xFFFFFF, true);
    }

    private static MutableText green(String value) {
        return tint(value, 0x9CFF6F, true);
    }

    private static MutableText red(String value) {
        return tint(value, 0xFF5577, true);
    }

    private static MutableText tint(String value, int rgb, boolean bold) {
        return Text.literal(value).styled(style -> style
                .withColor(TextColor.fromRgb(rgb)).withBold(bold));
    }

    private static MutableText gradient(String value, int start, int end) {
        MutableText text = Text.empty();
        if (value.isEmpty()) return text;
        for (int i = 0; i < value.length(); i++) {
            float t = value.length() == 1 ? 0.0f : (float) i / (value.length() - 1);
            int r = (int) (((start >> 16) & 0xFF) * (1.0f - t) + ((end >> 16) & 0xFF) * t);
            int g = (int) (((start >> 8) & 0xFF) * (1.0f - t) + ((end >> 8) & 0xFF) * t);
            int b = (int) ((start & 0xFF) * (1.0f - t) + (end & 0xFF) * t);
            text.append(tint(String.valueOf(value.charAt(i)), (r << 16) | (g << 8) | b, true));
        }
        return text;
    }

    private static void load() {
        if (!Files.isRegularFile(CONFIG)) return;
        try (Reader reader = Files.newBufferedReader(CONFIG)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("enabled")) enabled = root.get("enabled").getAsBoolean();
            if (root.has("selfEnabled")) selfEnabled = root.get("selfEnabled").getAsBoolean();
            if (root.has("selfColor")) {
                Integer rgb = parseColor(root.get("selfColor").getAsString());
                if (rgb != null) selfColor = rgb;
            }
            if (root.has("players") && root.get("players").isJsonObject()) {
                targets.clear();
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("players").entrySet()) {
                    try {
                        UUID id = UUID.fromString(entry.getKey());
                        JsonObject item = entry.getValue().getAsJsonObject();
                        String name = item.get("name").getAsString();
                        Integer rgb = parseColor(item.get("color").getAsString());
                        if (name != null && !name.isBlank() && rgb != null)
                            targets.put(id, new Target(name, rgb));
                    } catch (RuntimeException ignored) {
                        // Ignore malformed UUID entries but keep valid saved players.
                    }
                }
            }
        } catch (Exception ignored) {
            // Unreadable settings use defaults.
        }
    }

    private static void save() {
        try {
            Files.createDirectories(CONFIG.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("selfEnabled", selfEnabled);
            root.addProperty("selfColor", hex(selfColor));
            JsonObject players = new JsonObject();
            for (Map.Entry<UUID, Target> entry : targets.entrySet()) {
                JsonObject value = new JsonObject();
                value.addProperty("name", entry.getValue().name());
                value.addProperty("color", hex(entry.getValue().color()));
                players.add(entry.getKey().toString(), value);
            }
            root.add("players", players);
            Files.writeString(CONFIG, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception e) {
            failure(gray("Unable to save Glow settings ").append(dim("(" + e.getClass().getSimpleName() + ")")));
        }
    }
}
