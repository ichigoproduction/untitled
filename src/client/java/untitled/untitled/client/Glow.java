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
import net.minecraft.text.Text;

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
    private static int selfColor = DEFAULT_COLOR;

    private record Target(String name, int color) {}

    private Glow() {}

    public static void init() {
        load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("glow")
                    .executes(ctx -> {
                        notifyUser("Glow " + (enabled ? "ON" : "OFF")
                                + " | My color: " + hex(selfColor) + " | Players: " + targets.size());
                        return 1;
                    })
                    .then(literal("toggle").executes(ctx -> {
                        enabled = !enabled;
                        save();
                        notifyUser("Glow " + (enabled ? "ON" : "OFF"));
                        return 1;
                    }))
                    .then(literal("color")
                            .then(argument("color", StringArgumentType.word())
                                    .suggests((ctx, builder) -> suggestColors(builder))
                                    .executes(ctx -> {
                                        Integer value = parseColor(StringArgumentType.getString(ctx, "color"));
                                        if (value == null) {
                                            notifyUser("Unknown color. Use RRGGBB or a named color.");
                                            return 0;
                                        }
                                        selfColor = value;
                                        save();
                                        notifyUser("My Glow color: " + hex(selfColor));
                                        return 1;
                                    })))
                    .then(literal("remove")
                            .then(argument("player", StringArgumentType.word())
                                    .suggests((ctx, builder) -> suggestRegistered(builder))
                                    .executes(ctx -> remove(StringArgumentType.getString(ctx, "player")))))
                    .then(literal("clear").executes(ctx -> {
                        targets.clear();
                        save();
                        notifyUser("All registered players removed.");
                        return 1;
                    }))
                    .then(literal("list").executes(ctx -> {
                        notifyUser("Glow: " + (enabled ? "ON" : "OFF")
                                + " | Self: " + hex(selfColor) + " | Players: " + targets.size());
                        for (Target target : targets.values()) {
                            notifyUser("  " + target.name() + ": " + hex(target.color()));
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
            notifyUser("Unknown color: " + requestedColor);
            return 0;
        }
        UUID uuid = resolveUuid(name);
        if (uuid == null) {
            notifyUser("Player not found. Join the same server or use a previously registered name.");
            return 0;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null && uuid.equals(client.player.getUuid())) {
            if (rgb != null) {
                selfColor = rgb;
                save();
                notifyUser("My Glow color: " + hex(selfColor));
            } else {
                notifyUser("Your Glow is configured via /glow color <color>.");
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
        notifyUser("Glow target: " + canonicalName + " " + hex(assigned));
        return 1;
    }

    private static int remove(String name) {
        UUID uuid = resolveUuid(name);
        if (uuid == null || targets.remove(uuid) == null) {
            notifyUser("Not registered: " + name);
            return 0;
        }
        save();
        notifyUser("Glow target removed: " + name);
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
        if (player.getUuid().equals(client.player.getUuid())) return selfColor;
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

    private static void notifyUser(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.inGameHud != null)
            client.inGameHud.getChatHud().addMessage(Text.literal("[untitled] " + message));
    }

    private static void load() {
        if (!Files.isRegularFile(CONFIG)) return;
        try (Reader reader = Files.newBufferedReader(CONFIG)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("enabled")) enabled = root.get("enabled").getAsBoolean();
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
            notifyUser("Unable to save Glow settings: " + e.getMessage());
        }
    }
}
