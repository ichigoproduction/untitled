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
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private static final Map<String, CachedItem> CACHE = new LinkedHashMap<>();
    private static final int PAGE_SIZE = 20;

    private static boolean initialized = false;

    private ItemModelCache() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        loadSettings();
        registerCommands();
        ClientTickEvents.END_CLIENT_TICK.register(ItemModelCache::scanVisibleItems);
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

    private static void scanVisibleItems(MinecraftClient client) {
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

    private static int listCache(FabricClientCommandSource source, int page) {
        if (CACHE.isEmpty()) {
            source.sendFeedback(Text.literal("자동 모델 캐시가 비어 있습니다."));
            return 1;
        }

        int totalPages = Math.max(1, (CACHE.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page > totalPages) {
            source.sendError(Text.literal(
                    "존재하지 않는 페이지입니다: " + page + " (1-" + totalPages + ")"
            ));
            return 0;
        }

        source.sendFeedback(Text.literal(
                "자동 모델 캐시: " + CACHE.size() + "개 | " + page + "/" + totalPages + " 페이지"
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
            source.sendError(Text.literal("삭제할 캐시 아이템 이름을 입력해주세요."));
            return 0;
        }

        if (CACHE.remove(itemName) == null) {
            source.sendError(Text.literal("캐시에서 찾을 수 없습니다: " + itemName));
            return 0;
        }

        saveSettings();
        source.sendFeedback(Text.literal("모델 캐시 삭제: " + itemName));
        return 1;
    }

    private static int clearCache(FabricClientCommandSource source) {
        int count = CACHE.size();
        CACHE.clear();
        saveSettings();
        source.sendFeedback(Text.literal("모델 캐시 전체 삭제: " + count + "개"));
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

    private static void saveSettings() {
        try {
            Files.createDirectories(CACHE_PATH.getParent());

            JsonObject root = new JsonObject();
            JsonArray items = new JsonArray();
            for (Map.Entry<String, CachedItem> entry : CACHE.entrySet()) {
                JsonObject object = new JsonObject();
                object.addProperty("name", entry.getKey());
                object.addProperty("itemId", entry.getValue().itemId());
                object.addProperty("payload", entry.getValue().payload());
                items.add(object);
            }
            root.add("items", items);

            try (Writer writer = Files.newBufferedWriter(
                    CACHE_PATH,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
        }
    }
}
