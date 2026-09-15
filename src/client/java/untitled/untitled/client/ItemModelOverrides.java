package untitled.untitled.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class ItemModelOverrides {
    private enum RuleKind {
        VANILLA_ITEM,
        COPIED_STACK
    }

    private record ModelRule(RuleKind kind, String payload, String description) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("152_models.json");

    private static final ModelRules<ModelRule> RULES = new ModelRules<>();
    private static final Map<String, ItemStack> DECODED_COPY_STACKS = new HashMap<>();

    private static boolean initialized = false;
    private static boolean enabled = true;
    private static DynamicRegistryManager cachedRegistryManager = null;

    private ItemModelOverrides() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        loadSettings();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    literal("imodel")
                            .then(literal("toggle")
                                    .executes(context -> toggleEnabled(context.getSource())))
                            .then(literal("remove")
                                    .then(argument("name", StringArgumentType.greedyString())
                                            .executes(context -> removeRule(
                                                    context.getSource(),
                                                    ModelCommandParser.normalizeNameArgument(
                                                            StringArgumentType.getString(context, "name")
                                                    )
                                            ))))
                            .then(literal("clear")
                                    .executes(context -> clearRules(context.getSource())))
                            .then(literal("list")
                                    .executes(context -> listRules(context.getSource())))
                            .then(argument("mapping", StringArgumentType.greedyString())
                                    .suggests((context, builder) -> suggestVanillaItemsInMapping(builder))
                                    .executes(context -> setVanillaRuleFromMapping(
                                            context.getSource(),
                                            StringArgumentType.getString(context, "mapping")
                                    )))
            );

            dispatcher.register(
                    literal("imodelcopy")
                            .then(argument("name", StringArgumentType.greedyString())
                                    .executes(context -> copyHeldModel(
                                            context.getSource(),
                                            ModelCommandParser.normalizeNameArgument(
                                                    StringArgumentType.getString(context, "name")
                                            )
                                    )))
            );

            dispatcher.register(
                    literal("imodelcopyfrom")
                            .then(argument("mapping", StringArgumentType.greedyString())
                                    .executes(context -> copyCachedModel(
                                            context.getSource(),
                                            StringArgumentType.getString(context, "mapping")
                                    )))
            );
        });
    }

    public static ItemStack resolveFirstPersonStack(ItemStack original) {
        if (!enabled || original == null || original.isEmpty()) {
            return original;
        }

        ModelRule rule = RULES.get(original.getName().getString());
        if (rule == null) {
            return original;
        }

        return switch (rule.kind()) {
            case VANILLA_ITEM -> createVanillaStack(rule.payload(), original);
            case COPIED_STACK -> createCopiedStack(rule, original);
        };
    }

    private static int toggleEnabled(FabricClientCommandSource source) {
        enabled = !enabled;
        saveSettings();
        source.sendFeedback(Text.literal(
                "아이템 모델 변경: " + (enabled ? "ON" : "OFF")
        ));
        return 1;
    }

    private static int setVanillaRuleFromMapping(
            FabricClientCommandSource source,
            String rawMapping
    ) {
        ModelCommandParser.ModelMapping mapping =
                ModelCommandParser.parseModelMapping(rawMapping);
        if (mapping == null) {
            source.sendError(Text.literal(
                    "사용법: /imodel <아이템 이름> <minecraft:item_id>"
            ));
            return 0;
        }

        return setVanillaRule(source, mapping.itemName(), mapping.itemId());
    }

    private static int setVanillaRule(
            FabricClientCommandSource source,
            String itemName,
            String rawItemId
    ) {
        Identifier id = Identifier.tryParse(rawItemId);
        if (id == null
                || !"minecraft".equals(id.getNamespace())
                || !Registries.ITEM.containsId(id)) {
            source.sendError(Text.literal("바닐라 아이템 ID를 찾을 수 없습니다: " + rawItemId));
            return 0;
        }

        String name = validateName(source, itemName);
        if (name == null) {
            return 0;
        }

        RULES.put(name, new ModelRule(RuleKind.VANILLA_ITEM, id.toString(), id.toString()));
        DECODED_COPY_STACKS.remove(name);
        saveSettings();
        source.sendFeedback(Text.literal("모델 변경: " + name + " -> " + id));
        return 1;
    }

    private static int copyHeldModel(FabricClientCommandSource source, String itemName) {
        String name = validateName(source, itemName);
        if (name == null) {
            return 0;
        }

        ItemStack held = source.getPlayer().getMainHandStack();
        if (held.isEmpty()) {
            held = source.getPlayer().getOffHandStack();
        }
        if (held.isEmpty()) {
            source.sendError(Text.literal("먼저 복사할 모델의 아이템을 손에 들어주세요."));
            return 0;
        }

        try {
            ItemStack snapshot = held.copyWithCount(1);
            String snbt = snapshot.toNbt(source.getWorld().getRegistryManager()).toString();
            Identifier sourceId = Registries.ITEM.getId(snapshot.getItem());
            String description = "copy:" + sourceId;

            RULES.put(name, new ModelRule(RuleKind.COPIED_STACK, snbt, description));
            DECODED_COPY_STACKS.put(name, snapshot);
            cachedRegistryManager = source.getWorld().getRegistryManager();
            saveSettings();

            source.sendFeedback(Text.literal(
                    "모델 복사: " + name + " -> 현재 손 아이템 (" + sourceId + ")"
            ));
            return 1;
        } catch (Exception exception) {
            source.sendError(Text.literal("현재 손 아이템의 모델 데이터를 저장하지 못했습니다."));
            return 0;
        }
    }

    private static int copyCachedModel(
            FabricClientCommandSource source,
            String rawMapping
    ) {
        ModelCommandParser.CopyMapping mapping =
                ModelCommandParser.parseCopyMapping(rawMapping);
        if (mapping == null) {
            source.sendError(Text.literal(
                    "사용법: /imodelcopyfrom <원본 이름> <대상 이름>\n"
                            + "공백이 있는 이름은 따옴표로 감싸세요."
            ));
            return 0;
        }

        ItemModelCache.CachedItem cached = ItemModelCache.find(mapping.sourceName());
        if (cached == null) {
            source.sendError(Text.literal(
                    "자동 모델 캐시에서 찾을 수 없습니다: " + mapping.sourceName()
            ));
            return 0;
        }

        String targetName = validateName(source, mapping.targetName());
        if (targetName == null) {
            return 0;
        }

        String description = "cache:" + mapping.sourceName() + " (" + cached.itemId() + ")";
        RULES.put(
                targetName,
                new ModelRule(RuleKind.COPIED_STACK, cached.payload(), description)
        );
        DECODED_COPY_STACKS.remove(targetName);
        saveSettings();

        source.sendFeedback(Text.literal(
                "캐시 모델 복사: " + mapping.sourceName()
                        + " -> " + targetName
                        + " (" + cached.itemId() + ")"
        ));
        return 1;
    }

    private static int removeRule(FabricClientCommandSource source, String itemName) {
        String name = validateName(source, itemName);
        if (name == null) {
            return 0;
        }

        if (!RULES.remove(name)) {
            source.sendError(Text.literal("등록된 모델 규칙이 없습니다: " + name));
            return 0;
        }

        DECODED_COPY_STACKS.remove(name);
        saveSettings();
        source.sendFeedback(Text.literal("모델 규칙 삭제: " + name));
        return 1;
    }

    private static int clearRules(FabricClientCommandSource source) {
        int count = RULES.size();
        RULES.clear();
        DECODED_COPY_STACKS.clear();
        saveSettings();
        source.sendFeedback(Text.literal("모델 규칙 전체 삭제: " + count + "개"));
        return 1;
    }

    private static int listRules(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(
                "아이템 모델 변경 상태: " + (enabled ? "ON" : "OFF")
        ));

        if (RULES.size() == 0) {
            source.sendFeedback(Text.literal("등록된 모델 규칙이 없습니다."));
            return 1;
        }

        source.sendFeedback(Text.literal("등록된 모델 규칙: " + RULES.size() + "개"));
        for (Map.Entry<String, ModelRule> entry : RULES.entries()) {
            source.sendFeedback(Text.literal(
                    "- " + entry.getKey() + " -> " + entry.getValue().description()
            ));
        }
        return RULES.size();
    }

    private static CompletableFuture<Suggestions> suggestVanillaItemsInMapping(
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining();
        int lastSpace = remaining.lastIndexOf(' ');
        if (lastSpace < 0) {
            return builder.buildFuture();
        }

        String itemPrefix = remaining.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
        SuggestionsBuilder itemBuilder = builder.createOffset(
                builder.getStart() + lastSpace + 1
        );

        for (Identifier id : Registries.ITEM.getIds()) {
            if (!"minecraft".equals(id.getNamespace())) {
                continue;
            }

            String value = id.toString();
            if (itemPrefix.isEmpty() || value.startsWith(itemPrefix)) {
                itemBuilder.suggest(value);
            }
        }
        return itemBuilder.buildFuture();
    }

    private static String validateName(FabricClientCommandSource source, String itemName) {
        if (itemName == null || itemName.isBlank()) {
            source.sendError(Text.literal("아이템 이름은 비어 있을 수 없습니다."));
            return null;
        }
        return itemName;
    }

    private static ItemStack createVanillaStack(String rawId, ItemStack fallback) {
        Identifier id = Identifier.tryParse(rawId);
        if (id == null || !Registries.ITEM.containsId(id)) {
            return fallback;
        }

        Item item = Registries.ITEM.get(id);
        return new ItemStack(item);
    }

    private static ItemStack createCopiedStack(ModelRule rule, ItemStack fallback) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            return fallback;
        }

        DynamicRegistryManager registryManager = client.world.getRegistryManager();
        if (cachedRegistryManager != registryManager) {
            cachedRegistryManager = registryManager;
            DECODED_COPY_STACKS.clear();
        }

        String ruleName = fallback.getName().getString();
        ItemStack cached = DECODED_COPY_STACKS.get(ruleName);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        try {
            NbtCompound nbt = StringNbtReader.parse(rule.payload());
            ItemStack decoded = ItemStack.fromNbt(registryManager, nbt).orElse(ItemStack.EMPTY);
            if (decoded.isEmpty()) {
                return fallback;
            }
            DECODED_COPY_STACKS.put(ruleName, decoded);
            return decoded;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void loadSettings() {
        RULES.clear();
        DECODED_COPY_STACKS.clear();
        enabled = true;

        if (!Files.isRegularFile(CONFIG_PATH)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }

            if (root.has("enabled")) {
                enabled = root.get("enabled").getAsBoolean();
            }

            if (!root.has("rules") || !root.get("rules").isJsonArray()) {
                return;
            }

            JsonArray rules = root.getAsJsonArray("rules");
            for (JsonElement element : rules) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject object = element.getAsJsonObject();
                if (!object.has("name")
                        || !object.has("kind")
                        || !object.has("payload")) {
                    continue;
                }

                try {
                    String name = object.get("name").getAsString();
                    RuleKind kind = RuleKind.valueOf(object.get("kind").getAsString());
                    String payload = object.get("payload").getAsString();
                    String description = object.has("description")
                            ? object.get("description").getAsString()
                            : payload;

                    if (!name.isBlank()) {
                        RULES.put(name, new ModelRule(kind, payload, description));
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveSettings() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);

            JsonArray rules = new JsonArray();
            for (Map.Entry<String, ModelRule> entry : RULES.entries()) {
                JsonObject object = new JsonObject();
                object.addProperty("name", entry.getKey());
                object.addProperty("kind", entry.getValue().kind().name());
                object.addProperty("payload", entry.getValue().payload());
                object.addProperty("description", entry.getValue().description());
                rules.add(object);
            }
            root.add("rules", rules);

            try (Writer writer = Files.newBufferedWriter(
                    CONFIG_PATH,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
        }
    }
}
