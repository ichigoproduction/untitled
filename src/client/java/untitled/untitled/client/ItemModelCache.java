package untitled.untitled.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class ItemModelCache {
    record CachedItem(String itemId, String payload) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CACHE_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("152_item_model_cache.json");
    // Keep the toggle separate so switching it never rewrites the (potentially large) item cache.
    private static final Path STATE_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("152_item_model_cache_state.json");
    private static final Map<String, CachedItem> CACHE = new LinkedHashMap<>();
    private static final int PAGE_SIZE = 20;
    private static final Logger LOG = LoggerFactory.getLogger("untitled/ItemModelCache");
    private static final ScheduledExecutorService SAVE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "152-item-cache-writer");
                thread.setDaemon(true);
                return thread;
            });
    private static final Object SAVE_LOCK = new Object();
    private static ScheduledFuture<?> pendingWrite;
    private static final long SAVE_DEBOUNCE_MS = 1500L;

    private static boolean initialized = false;
    // Default ON for existing installations; a saved preference overrides this on launch.
    private static boolean autoCacheEnabled = false;

    private ItemModelCache() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        loadSettings();
        loadAutoCacheState();
        // Cache configuration is managed by AdditionalSettingsScreen.
        ClientTickEvents.END_CLIENT_TICK.register(ItemModelCache::scanVisibleItems);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> flushAndShutdown());
    }

    static CachedItem find(String itemName) {
        if (itemName == null) {
            return null;
        }
        return CACHE.get(itemName);
    }

    static Iterable<String> names() {
        return CACHE.keySet();
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        literal("imodelcache")
                                .then(literal("toggle")
                                        .executes(context -> toggleAutoCache(context.getSource())))
                                .then(literal("list")
                                        .executes(context -> listCache(context.getSource(), 1))
                                        .then(argument("page", IntegerArgumentType.integer(1))
                                                .executes(context -> listCache(
                                                        context.getSource(),
                                                        IntegerArgumentType.getInteger(context, "page")
                                                ))))
                                .then(literal("remove")
                                        .then(argument("name", StringArgumentType.greedyString())
                                                .executes(context -> removeCacheEntry(
                                                        context.getSource(),
                                                        ModelCommandParser.normalizeNameArgument(
                                                                StringArgumentType.getString(context, "name")
                                                        )
                                                ))))
                                .then(literal("clear")
                                        .executes(context -> clearCache(context.getSource())))
                )
        );
    }

    public static boolean settingsAutoCache() { return autoCacheEnabled; }
    public static void toggleSettingsAutoCache() {
        autoCacheEnabled = !autoCacheEnabled;
        saveAutoCacheState();
    }
    public static java.util.List<String> settingsEntries() {
        return new java.util.ArrayList<>(CACHE.keySet());
    }
    public static void settingsRemove(String name) {
        if (CACHE.remove(name) != null) saveSettings();
    }
    public static void settingsClear() {
        CACHE.clear();
        saveSettings();
    }

    private static void scanVisibleItems(MinecraftClient client) {
        if (!autoCacheEnabled) {
            return;
        }
        if (client == null || client.player == null || client.world == null) {
            return;
        }

        ScreenHandler handler = client.player.currentScreenHandler;
        if (handler == null) {
            return;
        }

        Map<String, CachedItem> observed = new LinkedHashMap<>();
        for (Slot slot : handler.slots) {
            collectStack(slot.getStack(), client, observed);
        }
        collectStack(handler.getCursorStack(), client, observed);

        boolean changed = false;
        for (Map.Entry<String, CachedItem> entry : observed.entrySet()) {
            CachedItem previous = CACHE.get(entry.getKey());
            if (!entry.getValue().equals(previous)) {
                CACHE.put(entry.getKey(), entry.getValue());
                changed = true;
            }
        }

        if (changed) {
            saveSettings();
        }
    }

    private static void collectStack(
            ItemStack stack,
            MinecraftClient client,
            Map<String, CachedItem> observed
    ) {
        if (stack == null || stack.isEmpty() || client.world == null) {
            return;
        }

        try {
            ItemStack snapshot = stack.copyWithCount(1);
            String name = snapshot.getName().getString();
            if (name == null || name.isBlank()) {
                return;
            }

            String payload = snapshot.toNbt(client.world.getRegistryManager()).toString();
            String itemId = Registries.ITEM.getId(snapshot.getItem()).toString();
            observed.put(name, new CachedItem(itemId, payload));
        } catch (Exception ignored) {
        }
    }

    private static int toggleAutoCache(FabricClientCommandSource source) {
        autoCacheEnabled = !autoCacheEnabled;
        saveAutoCacheState();
        source.sendFeedback(Text.literal("imodelcache → " + (autoCacheEnabled ? "ON" : "OFF")));
        return 1;
    }

    private static void loadAutoCacheState() {
        autoCacheEnabled = false;
        if (!Files.isRegularFile(STATE_PATH)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(STATE_PATH)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root != null && root.has("enabled")) {
                autoCacheEnabled = root.get("enabled").getAsBoolean();
            }
        } catch (Exception ignored) {
            // Keep the backwards-compatible ON default if settings are unreadable.
        }
    }

    private static void saveAutoCacheState() {
        try {
            Files.createDirectories(STATE_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", autoCacheEnabled);
            Files.writeString(STATE_PATH, GSON.toJson(root),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // The toggle still works this session if writing settings fails.
        }
    }

    private static int listCache(FabricClientCommandSource source, int page) {
        if (CACHE.isEmpty()) {
            source.sendFeedback(Text.literal("cache list : empty"));
            return 1;
        }

        int totalPages = Math.max(1, (CACHE.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) {
            source.sendError(Text.literal("Error:12"));
            return 0;
        }

        source.sendFeedback(Text.literal(
                "cache list : " + CACHE.size() + " | page " + page + "/" + totalPages
        ));

        int startIndex = (page - 1) * PAGE_SIZE;
        int endIndex = Math.min(startIndex + PAGE_SIZE, CACHE.size());
        int index = 0;
        for (Map.Entry<String, CachedItem> entry : CACHE.entrySet()) {
            if (index >= startIndex && index < endIndex) {
                source.sendFeedback(Text.literal(
                        "- " + entry.getKey() + " -> " + entry.getValue().itemId()
                ));
            }
            if (index >= endIndex) {
                break;
            }
            index++;
        }

        return endIndex - startIndex;
    }

    private static int removeCacheEntry(FabricClientCommandSource source, String itemName) {
        if (itemName == null || itemName.isBlank()) {
            source.sendError(Text.literal("Error:13"));
            return 0;
        }

        if (CACHE.remove(itemName) == null) {
            source.sendError(Text.literal("Error:14"));
            return 0;
        }

        saveSettings();
        source.sendFeedback(Text.literal("remove cache : " + itemName));
        return 1;
    }

    private static int clearCache(FabricClientCommandSource source) {
        int count = CACHE.size();
        CACHE.clear();
        saveSettings();
        source.sendFeedback(Text.literal("clear cache : " + count));
        return 1;
    }

    private static void loadSettings() {
        CACHE.clear();
        if (!Files.isRegularFile(CACHE_PATH)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(CACHE_PATH)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("items") || !root.get("items").isJsonArray()) {
                return;
            }

            JsonArray items = root.getAsJsonArray("items");
            for (JsonElement element : items) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject object = element.getAsJsonObject();
                if (!object.has("name")
                        || !object.has("itemId")
                        || !object.has("payload")) {
                    continue;
                }

                String name = object.get("name").getAsString();
                String itemId = object.get("itemId").getAsString();
                String payload = object.get("payload").getAsString();
                if (!name.isBlank() && !itemId.isBlank() && !payload.isBlank()) {
                    CACHE.put(name, new CachedItem(itemId, payload));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Keep disk I/O and JSON serialization off the client render/tick thread. */
    private static void saveSettings() {
        Map<String, CachedItem> snapshot = new LinkedHashMap<>(CACHE);
        synchronized (SAVE_LOCK) {
            if (pendingWrite != null) pendingWrite.cancel(false);
            pendingWrite = SAVE_EXECUTOR.schedule(
                    () -> writeSnapshot(snapshot), SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static void flushAndShutdown() {
        Map<String, CachedItem> snapshot = new LinkedHashMap<>(CACHE);
        synchronized (SAVE_LOCK) {
            if (pendingWrite != null) pendingWrite.cancel(false);
            pendingWrite = SAVE_EXECUTOR.schedule(() -> writeSnapshot(snapshot), 0L, TimeUnit.MILLISECONDS);
            SAVE_EXECUTOR.shutdown();
        }
        try {
            if (!SAVE_EXECUTOR.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warn("Timed out flushing item model cache during shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while flushing item model cache", e);
        }
    }

    private static void writeSnapshot(Map<String, CachedItem> snapshot) {
        Path temporary = null;
        try {
            Files.createDirectories(CACHE_PATH.getParent());
            JsonObject root = new JsonObject();
            JsonArray items = new JsonArray();
            for (Map.Entry<String, CachedItem> entry : snapshot.entrySet()) {
                JsonObject object = new JsonObject();
                object.addProperty("name", entry.getKey());
                object.addProperty("itemId", entry.getValue().itemId());
                object.addProperty("payload", entry.getValue().payload());
                items.add(object);
            }
            root.add("items", items);
            temporary = Files.createTempFile(CACHE_PATH.getParent(), "152_item_model_cache-", ".tmp");
            Files.writeString(temporary, GSON.toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, CACHE_PATH, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, CACHE_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException e) {
            LOG.warn("Failed to save item model cache", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { LOG.warn("Failed to delete temporary cache file", e); }
            }
        }
    }
}
