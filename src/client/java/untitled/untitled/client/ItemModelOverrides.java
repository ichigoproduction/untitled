package untitled.untitled.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;
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

    private enum RenderScope {
        FIRST_RIGHT("first_right"),
        FIRST_LEFT("first_left"),
        THIRD_RIGHT("third_right"),
        THIRD_LEFT("third_left"),
        GUI("gui"),
        EQUIPMENT("equipment");

        private final String commandName;

        RenderScope(String commandName) {
            this.commandName = commandName;
        }

        String commandName() {
            return commandName;
        }
    }

    private record ScopeSettings(
            boolean firstRight,
            boolean firstLeft,
            boolean thirdRight,
            boolean thirdLeft,
            boolean gui,
            boolean equipment
    ) {
        static ScopeSettings defaults() {
            return new ScopeSettings(true, true, false, false, false, false);
        }

        boolean enabled(RenderScope scope) {
            return switch (scope) {
                case FIRST_RIGHT -> firstRight;
                case FIRST_LEFT -> firstLeft;
                case THIRD_RIGHT -> thirdRight;
                case THIRD_LEFT -> thirdLeft;
                case GUI -> gui;
                case EQUIPMENT -> equipment;
            };
        }

        ScopeSettings toggle(RenderScope scope) {
            return switch (scope) {
                case FIRST_RIGHT -> new ScopeSettings(
                        !firstRight, firstLeft, thirdRight, thirdLeft, gui, equipment
                );
                case FIRST_LEFT -> new ScopeSettings(
                        firstRight, !firstLeft, thirdRight, thirdLeft, gui, equipment
                );
                case THIRD_RIGHT -> new ScopeSettings(
                        firstRight, firstLeft, !thirdRight, thirdLeft, gui, equipment
                );
                case THIRD_LEFT -> new ScopeSettings(
                        firstRight, firstLeft, thirdRight, !thirdLeft, gui, equipment
                );
                case GUI -> new ScopeSettings(
                        firstRight, firstLeft, thirdRight, thirdLeft, !gui, equipment
                );
                case EQUIPMENT -> new ScopeSettings(
                        firstRight, firstLeft, thirdRight, thirdLeft, gui, !equipment
                );
            };
        }

        ScopeSettings toggleAll() {
            boolean allEnabled = firstRight
                    && firstLeft
                    && thirdRight
                    && thirdLeft
                    && gui
                    && equipment;
            boolean next = !allEnabled;
            return new ScopeSettings(next, next, next, next, next, next);
        }

        String summary() {
            StringBuilder result = new StringBuilder();
            appendScope(result, firstRight, "first_right");
            appendScope(result, firstLeft, "first_left");
            appendScope(result, thirdRight, "third_right");
            appendScope(result, thirdLeft, "third_left");
            appendScope(result, gui, "gui");
            appendScope(result, equipment, "equipment");
            return result.isEmpty() ? "none" : result.toString();
        }

        private static void appendScope(StringBuilder target, boolean enabled, String name) {
            if (!enabled) {
                return;
            }
            if (!target.isEmpty()) {
                target.append(',');
            }
            target.append(name);
        }
    }

    private record ModelRule(
            RuleKind kind,
            String payload,
            String drawnPayload,
            String description,
            ScopeSettings scopes
    ) {
        boolean hasDrawn() {
            return kind == RuleKind.COPIED_STACK
                    && drawnPayload != null
                    && !drawnPayload.isBlank();
        }
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
                            .then(literal("scope")
                                    .then(argument("target", UnicodeStringArgumentType.string())
                                            .suggests((context, builder) -> suggestRuleNames(builder))
                                            .then(scopeToggleNode(RenderScope.FIRST_RIGHT))
                                            .then(scopeToggleNode(RenderScope.FIRST_LEFT))
                                            .then(scopeToggleNode(RenderScope.THIRD_RIGHT))
                                            .then(scopeToggleNode(RenderScope.THIRD_LEFT))
                                            .then(scopeToggleNode(RenderScope.GUI))
                                            .then(scopeToggleNode(RenderScope.EQUIPMENT))
                                            .then(literal("all")
                                                    .then(literal("toggle")
                                                            .executes(context -> toggleAllScopes(
                                                                    context.getSource(),
                                                                    StringArgumentType.getString(
                                                                            context,
                                                                            "target"
                                                                    )
                                                            ))))))
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
                                    .suggests((context, builder) -> suggestCachedSourceNames(builder))
                                    .executes(context -> copyCachedModel(
                                            context.getSource(),
                                            StringArgumentType.getString(context, "mapping")
                                    )))
            );
        });
    }

    static boolean hasDrawnState(String itemName) {
        if (itemName == null) {
            return false;
        }
        ModelRule rule = RULES.get(itemName);
        return rule != null && rule.hasDrawn();
    }

    public static ItemStack resolveFirstPersonStack(
            ItemStack original,
            ModelTransformationMode mode,
            LivingEntity entity
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || entity != client.player) {
            return original;
        }

        RenderScope scope = switch (mode) {
            case FIRST_PERSON_RIGHT_HAND -> RenderScope.FIRST_RIGHT;
            case FIRST_PERSON_LEFT_HAND -> RenderScope.FIRST_LEFT;
            default -> null;
        };
        return scope == null ? original : resolveForScope(original, scope, true);
    }

    public static ItemStack resolveThirdPersonStack(
            ItemStack original,
            ModelTransformationMode mode,
            LivingEntity entity
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || entity != client.player) {
            return original;
        }

        RenderScope scope = switch (mode) {
            case THIRD_PERSON_RIGHT_HAND -> RenderScope.THIRD_RIGHT;
            case THIRD_PERSON_LEFT_HAND -> RenderScope.THIRD_LEFT;
            default -> null;
        };
        return scope == null ? original : resolveForScope(original, scope, true);
    }

    public static ItemStack resolveGuiStack(ItemStack original, ModelTransformationMode mode) {
        if (mode != ModelTransformationMode.GUI) {
            return original;
        }
        return resolveForScope(original, RenderScope.GUI, false);
    }

    public static ItemStack resolveEquipmentStack(ItemStack original) {
        return resolveForScope(original, RenderScope.EQUIPMENT, false);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> scopeToggleNode(
            RenderScope scope
    ) {
        return literal(scope.commandName())
                .then(literal("toggle")
                        .executes(context -> toggleScope(
                                context.getSource(),
                                StringArgumentType.getString(context, "target"),
                                scope
                        )));
    }

    private static int toggleEnabled(FabricClientCommandSource source) {
        enabled = !enabled;
        ItemModelRuntime.reset();
        saveSettings();
        source.sendFeedback(Text.literal(
                "imodel : " + (enabled ? "ON" : "OFF")
        ));
        return 1;
    }

    private static int toggleScope(
            FabricClientCommandSource source,
            String targetName,
            RenderScope scope
    ) {
        ModelRule rule = RULES.get(targetName);
        if (rule == null) {
            source.sendError(Text.literal("Error:4"));
            return 0;
        }

        ScopeSettings scopes = rule.scopes().toggle(scope);
        RULES.put(targetName, new ModelRule(
                rule.kind(),
                rule.payload(),
                rule.drawnPayload(),
                rule.description(),
                scopes
        ));
        ItemModelRuntime.reset();
        saveSettings();

        source.sendFeedback(Text.literal(
                "model scope " + scope.commandName() + " : "
                        + (scopes.enabled(scope) ? "ON" : "OFF")
                        + " (" + targetName + ")"
        ));
        return 1;
    }

    private static int toggleAllScopes(
            FabricClientCommandSource source,
            String targetName
    ) {
        ModelRule rule = RULES.get(targetName);
        if (rule == null) {
            source.sendError(Text.literal("Error:4"));
            return 0;
        }

        ScopeSettings scopes = rule.scopes().toggleAll();
        RULES.put(targetName, new ModelRule(
                rule.kind(),
                rule.payload(),
                rule.drawnPayload(),
                rule.description(),
                scopes
        ));
        ItemModelRuntime.reset();
        saveSettings();

        source.sendFeedback(Text.literal(
                "model scope all : " + scopes.summary() + " (" + targetName + ")"
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
            source.sendError(Text.literal("Error:1"));
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
            source.sendError(Text.literal("Error:2"));
            return 0;
        }

        String name = validateName(source, itemName, "Error:3");
        if (name == null) {
            return 0;
        }

        ScopeSettings scopes = scopesForExistingRule(name);
        RULES.put(name, new ModelRule(
                RuleKind.VANILLA_ITEM,
                id.toString(),
                null,
                id.toString(),
                scopes
        ));
        invalidateRuleCache(name);
        ItemModelRuntime.reset();
        saveSettings();
        source.sendFeedback(Text.literal("model : " + name + " -> " + id));
        return 1;
    }

    private static int copyHeldModel(FabricClientCommandSource source, String itemName) {
        String name = validateName(source, itemName, "Error:7");
        if (name == null) {
            return 0;
        }

        ItemStack held = source.getPlayer().getMainHandStack();
        if (held.isEmpty()) {
            held = source.getPlayer().getOffHandStack();
        }
        if (held.isEmpty()) {
            source.sendError(Text.literal("Error:5"));
            return 0;
        }

        try {
            ItemStack snapshot = held.copyWithCount(1);
            String snbt = snapshot.toNbt(source.getWorld().getRegistryManager()).toString();
            Identifier sourceId = Registries.ITEM.getId(snapshot.getItem());
            String description = "copy:" + sourceId;
            ScopeSettings scopes = scopesForExistingRule(name);

            RULES.put(name, new ModelRule(
                    RuleKind.COPIED_STACK,
                    snbt,
                    null,
                    description,
                    scopes
            ));
            invalidateRuleCache(name);
            cachedRegistryManager = source.getWorld().getRegistryManager();
            ItemModelRuntime.reset();
            saveSettings();

            source.sendFeedback(Text.literal(
                    "model copy : " + name + " -> held item (" + sourceId + ")"
            ));
            return 1;
        } catch (Exception exception) {
            source.sendError(Text.literal("Error:6"));
            return 0;
        }
    }

    private static int copyCachedModel(
            FabricClientCommandSource source,
            String rawMapping
    ) {
        ModelCommandParser.CopyStateMapping mapping =
                ModelCommandParser.parseCopyStateMapping(rawMapping);
        if (mapping == null) {
            source.sendError(Text.literal("Error:9"));
            return 0;
        }

        ItemModelCache.CachedItem cached = ItemModelCache.find(mapping.sourceName());
        if (cached == null) {
            source.sendError(Text.literal("Error:8"));
            return 0;
        }

        String targetName = validateName(source, mapping.targetName(), "Error:9");
        if (targetName == null) {
            return 0;
        }

        String description = "cache:" + mapping.sourceName() + " (" + cached.itemId() + ")";
        ModelRule existing = RULES.get(targetName);
        ScopeSettings scopes = existing == null
                ? ScopeSettings.defaults()
                : existing.scopes();

        if (mapping.state() == null) {
            RULES.put(
                    targetName,
                    new ModelRule(
                            RuleKind.COPIED_STACK,
                            cached.payload(),
                            null,
                            description,
                            scopes
                    )
            );
            invalidateRuleCache(targetName);
            ItemModelRuntime.reset();
            saveSettings();

            source.sendFeedback(Text.literal(
                    "cache copy : " + mapping.sourceName()
                            + " -> " + targetName
                            + " (" + cached.itemId() + ")"
            ));
            return 1;
        }

        if (mapping.state().equals("sheathed")) {
            String drawnPayload = existing != null
                    && existing.kind() == RuleKind.COPIED_STACK
                    ? existing.drawnPayload()
                    : null;

            RULES.put(
                    targetName,
                    new ModelRule(
                            RuleKind.COPIED_STACK,
                            cached.payload(),
                            drawnPayload,
                            description,
                            scopes
                    )
            );
            invalidateRuleCache(targetName);
            ItemModelRuntime.reset();
            saveSettings();

            source.sendFeedback(Text.literal(
                    "save model 1 : " + mapping.sourceName()
                            + " -> " + targetName
                            + " (" + cached.itemId() + ")"
            ));
            return 1;
        }

        if (existing == null
                || existing.kind() != RuleKind.COPIED_STACK
                || existing.payload() == null
                || existing.payload().isBlank()) {
            source.sendError(Text.literal("Error:10"));
            return 0;
        }

        RULES.put(
                targetName,
                new ModelRule(
                        RuleKind.COPIED_STACK,
                        existing.payload(),
                        cached.payload(),
                        existing.description(),
                        scopes
                )
        );
        invalidateRuleCache(targetName);
        ItemModelRuntime.reset();
        saveSettings();

        source.sendFeedback(Text.literal(
                "save model 2 : " + mapping.sourceName()
                        + " -> " + targetName
                        + " (" + cached.itemId() + ")"
        ));
        return 1;
    }

    private static int removeRule(FabricClientCommandSource source, String itemName) {
        String name = validateName(source, itemName, "Error:11");
        if (name == null) {
            return 0;
        }

        if (!RULES.remove(name)) {
            source.sendError(Text.literal("Error:11"));
            return 0;
        }

        invalidateRuleCache(name);
        ItemModelRuntime.reset();
        saveSettings();
        source.sendFeedback(Text.literal("remove model : " + name));
        return 1;
    }

    private static int clearRules(FabricClientCommandSource source) {
        int count = RULES.size();
        RULES.clear();
        DECODED_COPY_STACKS.clear();
        ItemModelRuntime.reset();
        saveSettings();
        source.sendFeedback(Text.literal("clear models : " + count));
        return 1;
    }

    private static int listRules(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal(
                "imodel : " + (enabled ? "ON" : "OFF")
        ));

        if (RULES.size() == 0) {
            source.sendFeedback(Text.literal("model list : empty"));
            return 1;
        }

        source.sendFeedback(Text.literal("model list : " + RULES.size()));
        for (Map.Entry<String, ModelRule> entry : RULES.entries()) {
            ModelRule rule = entry.getValue();
            String state = rule.hasDrawn() ? "model 1+2" : "static";
            String description = rule.description();
            if (description.startsWith("cache:")) {
                description = "cache : " + description.substring("cache:".length());
            } else if (description.startsWith("copy:")) {
                description = "copy : " + description.substring("copy:".length());
            } else {
                description = "model : " + description;
            }

            source.sendFeedback(Text.literal(
                    "- " + entry.getKey()
                            + " -> " + description
                            + " | state : " + state
                            + " | scope : " + rule.scopes().summary()
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

    private static CompletableFuture<Suggestions> suggestCachedSourceNames(
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining();
        if (remaining.indexOf(' ') >= 0 && !remaining.startsWith("\"")) {
            return builder.buildFuture();
        }
        if (remaining.startsWith("\"") && remaining.indexOf('"', 1) >= 0) {
            return builder.buildFuture();
        }

        String normalizedPrefix = remaining.startsWith("\"")
                ? remaining.substring(1).toLowerCase(Locale.ROOT)
                : remaining.toLowerCase(Locale.ROOT);

        for (String name : ItemModelCache.names()) {
            if (!name.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)) {
                continue;
            }

            String suggestion = name.contains(" ") ? "\"" + name + "\"" : name;
            builder.suggest(suggestion);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestRuleNames(
            SuggestionsBuilder builder
    ) {
        String prefix = builder.getRemaining().toLowerCase(Locale.ROOT).replace("\"", "");
        for (Map.Entry<String, ModelRule> entry : RULES.entries()) {
            String name = entry.getKey();
            if (!name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            builder.suggest(name.contains(" ") ? "\"" + name + "\"" : name);
        }
        return builder.buildFuture();
    }

    private static String validateName(
            FabricClientCommandSource source,
            String itemName,
            String errorCode
    ) {
        if (itemName == null || itemName.isBlank()) {
            source.sendError(Text.literal(errorCode));
            return null;
        }
        return itemName;
    }

    private static ScopeSettings scopesForExistingRule(String itemName) {
        ModelRule existing = RULES.get(itemName);
        return existing == null ? ScopeSettings.defaults() : existing.scopes();
    }

    private static ItemStack resolveForScope(
            ItemStack original,
            RenderScope scope,
            boolean allowDrawn
    ) {
        if (!enabled || original == null || original.isEmpty()) {
            return original;
        }

        String name = original.getName().getString();
        ModelRule rule = RULES.get(name);
        if (rule == null || !rule.scopes().enabled(scope)) {
            return original;
        }

        return switch (rule.kind()) {
            case VANILLA_ITEM -> createVanillaStack(rule.payload(), original);
            case COPIED_STACK -> {
                boolean drawn = allowDrawn
                        && rule.hasDrawn()
                        && ItemModelRuntime.isDrawn(name);
                String payload = drawn ? rule.drawnPayload() : rule.payload();
                String cacheKey = name + (drawn ? "|drawn" : "|base");
                yield createCopiedStack(payload, cacheKey, original);
            }
        };
    }

    private static ItemStack createVanillaStack(String rawId, ItemStack fallback) {
        Identifier id = Identifier.tryParse(rawId);
        if (id == null || !Registries.ITEM.containsId(id)) {
            return fallback;
        }

        Item item = Registries.ITEM.get(id);
        return new ItemStack(item);
    }

    private static ItemStack createCopiedStack(
            String payload,
            String cacheKey,
            ItemStack fallback
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || payload == null || payload.isBlank()) {
            return fallback;
        }

        DynamicRegistryManager registryManager = client.world.getRegistryManager();
        if (cachedRegistryManager != registryManager) {
            cachedRegistryManager = registryManager;
            DECODED_COPY_STACKS.clear();
        }

        ItemStack cached = DECODED_COPY_STACKS.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        try {
            NbtCompound nbt = StringNbtReader.parse(payload);
            ItemStack decoded = ItemStack.fromNbt(registryManager, nbt).orElse(ItemStack.EMPTY);
            if (decoded.isEmpty()) {
                return fallback;
            }
            DECODED_COPY_STACKS.put(cacheKey, decoded);
            return decoded;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void invalidateRuleCache(String itemName) {
        DECODED_COPY_STACKS.remove(itemName + "|base");
        DECODED_COPY_STACKS.remove(itemName + "|drawn");
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
                    String drawnPayload = object.has("drawnPayload")
                            ? object.get("drawnPayload").getAsString()
                            : null;
                    String description = object.has("description")
                            ? object.get("description").getAsString()
                            : payload;
                    ScopeSettings scopes = readScopes(object);

                    if (!name.isBlank()) {
                        RULES.put(
                                name,
                                new ModelRule(
                                        kind,
                                        payload,
                                        drawnPayload,
                                        description,
                                        scopes
                                )
                        );
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static ScopeSettings readScopes(JsonObject object) {
        ScopeSettings defaults = ScopeSettings.defaults();
        return new ScopeSettings(
                readBoolean(object, "scopeFirstRight", defaults.firstRight()),
                readBoolean(object, "scopeFirstLeft", defaults.firstLeft()),
                readBoolean(object, "scopeThirdRight", defaults.thirdRight()),
                readBoolean(object, "scopeThirdLeft", defaults.thirdLeft()),
                readBoolean(object, "scopeGui", defaults.gui()),
                readBoolean(object, "scopeEquipment", defaults.equipment())
        );
    }

    private static boolean readBoolean(
            JsonObject object,
            String key,
            boolean defaultValue
    ) {
        return object.has(key) ? object.get(key).getAsBoolean() : defaultValue;
    }

    private static void saveSettings() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            JsonArray rules = new JsonArray();
            for (Map.Entry<String, ModelRule> entry : RULES.entries()) {
                ModelRule rule = entry.getValue();
                JsonObject object = new JsonObject();
                object.addProperty("name", entry.getKey());
                object.addProperty("kind", rule.kind().name());
                object.addProperty("payload", rule.payload());
                if (rule.drawnPayload() != null && !rule.drawnPayload().isBlank()) {
                    object.addProperty("drawnPayload", rule.drawnPayload());
                }
                object.addProperty("description", rule.description());
                object.addProperty("scopeFirstRight", rule.scopes().firstRight());
                object.addProperty("scopeFirstLeft", rule.scopes().firstLeft());
                object.addProperty("scopeThirdRight", rule.scopes().thirdRight());
                object.addProperty("scopeThirdLeft", rule.scopes().thirdLeft());
                object.addProperty("scopeGui", rule.scopes().gui());
                object.addProperty("scopeEquipment", rule.scopes().equipment());
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
