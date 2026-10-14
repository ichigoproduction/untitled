package untitled.untitled.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class SkinChanger {
    // =========================================================
    // 0) Tuning
    // =========================================================
    /** 진행 단계 메시지(Resolving / Fetching 등)를 보여줄지 */
    private static boolean SHOW_STEPS = true;
    /** 성공/에러에 붙는 소스 태그를 보여줄지 ([TAB]/[API]) */
    private static boolean SHOW_SOURCE_TAG = true;

    // =========================================================
    // 0.5) Persist
    // =========================================================
    private static final Path COSMETIC_CFG = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("152_cosmetics.json");
    private static final AtomicReference<Persist> PERSIST = new AtomicReference<>(Persist.load(COSMETIC_CFG));
    private static volatile boolean AUTO_RESTORE_TRIED = false;

    // =========================================================
    // 1) State
    // =========================================================
    private static final AtomicReference<SkinTextures> OVERRIDE = new AtomicReference<>(null);
    private static final AtomicReference<String> OVERRIDE_FROM = new AtomicReference<>(null);

    private static final AtomicReference<Identifier> CAPE_OVERRIDE = new AtomicReference<>(null);
    private static final AtomicReference<Identifier> ELYTRA_OVERRIDE = new AtomicReference<>(null);
    private static final AtomicReference<String> CAPE_FROM = new AtomicReference<>(null);

    private static final AtomicLong REQ_SEQ = new AtomicLong(0L);
    private static final AtomicLong ACTIVE_REQ = new AtomicLong(0L);
    private static final AtomicLong CAPE_REQ_SEQ = new AtomicLong(0L);
    private static final AtomicLong ACTIVE_CAPE_REQ = new AtomicLong(0L);


    private static final ConcurrentHashMap<String, UuidCache> UUID_CACHE = new ConcurrentHashMap<>();
    private static final long UUID_CACHE_OK_MS = 10 * 60 * 1000L;
    private static final long UUID_CACHE_NEG_MS = 60 * 1000L;

    private static final ConcurrentHashMap<UUID, TexCache> TEX_CACHE = new ConcurrentHashMap<>();
    private static final long TEX_CACHE_OK_MS = 10 * 60 * 1000L;
    private static final long TEX_CACHE_NEG_MS = 60 * 1000L;

    // URL -> registered Identifier (dynamic texture)
    private static final ConcurrentHashMap<String, Identifier> DYN_TEX_CACHE = new ConcurrentHashMap<>();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    private enum SourceTag { TAB, API }

    // =========================================================
    // 2) Built-in Cape Map (Name -> URL candidates)
    // =========================================================
    // ⚠️ 3rd-party URL은 바뀔 수 있습니다. (그래서 후보 여러 개를 순차 시도)
    // =========================================================
// 2) Built-in Cape Map (Name -> URL candidates)
// =========================================================
// ⚠️ 3rd-party URL은 바뀔 수 있습니다. (그래서 후보 여러 개를 순차 시도)
    private static final Map<String, List<String>> BUILTIN_CAPES = Map.ofEntries(
            // Migrator cape
            Map.entry("migrator", List.of(
                    "https://textures.minecraft.net/texture/2340c0e03dd24a11b15a8b33c2a7e9e32abb2051b2481d0ba7defd635ca7a933"
            )),

            // ✅ Experience cape
            Map.entry("experience", List.of(
                    "https://textures.minecraft.net/texture/7658c5025c77cfac7574aab3af94a46a8886e3b7722a895255fbf22ab8652434"
            )),

            // ✅ Cherry Blossom cape
            Map.entry("cherry", List.of(
                    "https://textures.minecraft.net/texture/afd553b39358a24edfe3b8a9a939fa5fa4faa4d9a9c3d6af8eafb377fa05c2bb"
            )),

            // ✅ Purple Heart cape
            Map.entry("twitch", List.of(
                    "https://textures.minecraft.net/texture/cb40a92e32b57fd732a00fc325e7afb00a7ca74936ad50d8e860152e482cfbde"
            )),

            // ✅ 15th Anniversary cape
            Map.entry("creeper", List.of(
                    "https://textures.minecraft.net/texture/cd9d82ab17fd92022dbd4a86cde4c382a7540e117fae7b9a2853658505a80625"
            )),

            Map.entry("prismarine", List.of(
                    "https://textures.minecraft.net/texture/d8f8d13a1adf9636a16c31d47f3ecc9bb8d8533108aa5ad2a01b13b1a0c55eac"
            )),

            Map.entry("vanilla", List.of(
                    "https://textures.minecraft.net/texture/f9a76537647989f9a0b6d001e320dac591c359e9e61a31f4ce11c88f207f0ad4"
            )),

            Map.entry("copper", List.of(
                    "https://textures.minecraft.net/texture/5e6f3193e74cd16cdd6637d9bae5484e3a37ff2a14c2d157c659a07810b1bdca"
            )),

            Map.entry("founders", List.of(
                    "https://textures.minecraft.net/texture/99aba02ef05ec6aa4d42db8ee43796d6cd50e4b2954ab29f0caeb85f96bf52a1"
            )),

            Map.entry("translator", List.of(
                    "https://textures.minecraft.net/texture/1bf91499701404e21bd46b0191d63239a4ef76ebde88d27e4d430ac211df681e"
            )),

            Map.entry("cobalt", List.of(
                    "https://textures.minecraft.net/texture/ca35c56efe71ed290385f4ab5346a1826b546a54d519e6a3ff01efa01acce81"
            )),

            Map.entry("tiktok", List.of(
                    "https://textures.minecraft.net/texture/569b7f2a1d00d26f30efe3f9ab9ac817b1e6d35f4f3cfb0324ef2d328223d350"
            )),

            Map.entry("pan", List.of(
                    "https://textures.minecraft.net/texture/28de4a81688ad18b49e735a273e086c18f1e3966956123ccb574034c06f5d336"
            )),

            Map.entry("menace", List.of(
                    "https://textures.minecraft.net/texture/dbc21e222528e30dc88445314f7be6ff12d3aeebc3c192054fba7e3b3f8c77b1"
            )),

            Map.entry("minecon2011", List.of(
                    "https://textures.minecraft.net/texture/953cac8b779fe41383e675ee2b86071a71658f2180f56fbce8aa315ea70e2ed6"
            )),

            Map.entry("minecon2013", List.of(
                    "https://textures.minecraft.net/texture/153b1a0dfcbae953cdeb6f2c2bf6bf79943239b1372780da44bcbb29273131da"
            )),

            Map.entry("15th", List.of(
                    "https://textures.minecraft.net/texture/56c35628fe1c4d59dd52561a3d03bfa4e1a76d397c8b9c476c2f77cb6aebb1df"
            )),

            Map.entry("common", List.of(
                    "https://textures.minecraft.net/texture/5ec930cdd2629c8771655c60eebeb867b4b6559b0e6d3bc71c40c96347fa03f0"
            )),

            Map.entry("realms", List.of(
                    "https://textures.minecraft.net/texture/17912790ff164b93196f08ba71d0e62129304776d0f347334f8a6eae509f8a56"
            )),

            Map.entry("minecon2012", List.of(
                    "https://textures.minecraft.net/texture/a2e8d97ec79100e90a75d369d1b3ba81273c4f82bc1b737e934eed4a854be1b6"
            )),

            Map.entry("office", List.of(
                    "https://textures.minecraft.net/texture/5c29410057e32abec02d870ecb52ec25fb45ea81e785a7854ae8429d7236ca26"
            )),

            Map.entry("home", List.of(
                    "https://textures.minecraft.net/texture/1de21419009db483900da6298a1e6cbf9f1bc1523a0dcdc16263fab150693edd"
            )),

            Map.entry("yearn", List.of(
                    "https://textures.minecraft.net/texture/308b32a9e303155a0b4262f9e5483ad4a22e3412e84fe8385a0bdd73dc41fa89"
            )),

            Map.entry("minecon2016", List.of(
                    "https://textures.minecraft.net/texture/e7dfea16dc83c97df01a12fabbd1216359c0cd0ea42f9999b6e97c584963e980"
            )),

            Map.entry("minecon2015", List.of(
                    "https://textures.minecraft.net/texture/b0cc08840700447322d953a02b965f1d65a13a603bf64b17c803c21446fe1635"
            )),

            Map.entry("mojang", List.of(
                    "https://textures.minecraft.net/texture/5786fe99be377dfb6858859f926c4dbc995751e91cee373468c5fbf4865e7151"
            )),

            Map.entry("millionth", List.of(
                    "https://textures.minecraft.net/texture/70efffaf86fe5bc089608d3cb297d3e276b9eb7a8f9f2fe6659c23a2d8b18edf"
            )),

            Map.entry("studio", List.of(
                    "https://textures.minecraft.net/texture/9e507afc56359978a3eb3e32367042b853cddd0995d17d0da995662913fb00f7"
            )),

            Map.entry("mojira", List.of(
                    "https://textures.minecraft.net/texture/ae677f7d98ac70a533713518416df4452fe5700365c09cf45d0d156ea9396551"
            )),

            Map.entry("classic", List.of(
                    "https://textures.minecraft.net/texture/8f120319222a9f4a104e2f5cb97b2cda93199a2ee9e1585cb8d09d6f687cb761"
            )),

            Map.entry("scrolls", List.of(
                    "https://textures.minecraft.net/texture/3efadf6510961830f9fcc077f19b4daf286d502b5f5aafbd807c7bbffcaca245"
            )),

            Map.entry("turtle", List.of(
                    "https://textures.minecraft.net/texture/5048ea61566353397247d2b7d946034de926b997d5e66c86483dfb1e031aee95"
            )),

            Map.entry("db", List.of(
                    "https://textures.minecraft.net/texture/bcfbe84c6542a4a5c213c1cacf8979b5e913dcb4ad783a8b80e3c4a7d5c8bdac"
            )),

            Map.entry("snowman", List.of(
                    "https://textures.minecraft.net/texture/23ec737f18bfe4b547c95935fc297dd767bb84ee55bfd855144d279ac9bfd9fe"
            )),

            Map.entry("spade", List.of(
                    "https://textures.minecraft.net/texture/2e002d5e1758e79ba51d08d92a0f3a95119f2f435ae7704916507b6c565a7da8"
            )),

            Map.entry("zombie", List.of(
                    "https://textures.minecraft.net/texture/a3f6e4f14801f3ea55e3d95b9b4ef3b5e8802d947f669de93d6ec4b9354a436b"
            ))

    );

    // =========================================================
    // 3) Suggestions
    // =========================================================
    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_PLAYERS = (ctx, builder) -> {
        MinecraftClient client = ctx.getSource().getClient();
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        String rem = builder.getRemaining() == null ? "" : builder.getRemaining().trim();
        String remLow = rem.toLowerCase(Locale.ROOT);

        if (nh != null) {
            for (PlayerListEntry e : nh.getPlayerList()) {
                GameProfile p = e.getProfile();
                if (p == null || p.getName() == null) continue;
                String n = p.getName();
                if (!remLow.isEmpty() && !n.toLowerCase(Locale.ROOT).startsWith(remLow)) continue;
                builder.suggest(n);
            }
        }
        // /skin reset
        if (remLow.isEmpty() || "reset".startsWith(remLow)) builder.suggest("reset");
        return builder.buildFuture();
    };

    private static final SuggestionProvider<FabricClientCommandSource> SUGGEST_CAPES = (ctx, builder) -> {
        String rem = builder.getRemaining() == null ? "" : builder.getRemaining().trim();
        String remLow = rem.toLowerCase(Locale.ROOT);
        for (String k : BUILTIN_CAPES.keySet()) {
            if (!remLow.isEmpty() && !k.startsWith(remLow)) continue;
            builder.suggest(prettyName(k));
        }
        return builder.buildFuture();
    };

    // =========================================================
    // 4) Init / Command
    // =========================================================
    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));

        // 자동 복구: 월드/서버 들어간 뒤 1회만 시도
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null) return;
            tryAutoRestore(client);
        });
    }

    private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                literal("skin")
                        .executes(ctx -> {
                            emitInfo(ctx.getSource(), Msg.usageLine());
                            return 1;
                        })
                        // ✅ /skin reset: 스킨만 reset
                        .then(literal("reset").executes(ctx -> {
                            clearOverride();                    // ✅ 스킨만 끔
                            emitInfo(ctx.getSource(), Msg.skinResetLine());
                            persistSkin(null, false);          // ✅ 스킨 저장만 끔
                            return 1;
                        }))
                        .then(
                                argument("player", StringArgumentType.word())
                                        .suggests(SUGGEST_PLAYERS)
                                        .executes(ctx -> applyFromName(ctx.getSource(), StringArgumentType.getString(ctx, "player")))
                        )
        );

        dispatcher.register(
                literal("cape")
                        .executes(ctx -> {
                            emitInfo(ctx.getSource(), Msg.capeUsageLine());
                            return 1;
                        })
                        // ✅ /cape reset: 망토만 reset
                        .then(literal("reset").executes(ctx -> {
                            clearCapeOverride();               // ✅ 망토만 끔
                            emitInfo(ctx.getSource(), Msg.capeResetLine());
                            persistCape(null, false);          // ✅ 망토 저장만 끔
                            return 1;
                        }))
                        .then(
                                argument("name", StringArgumentType.word())
                                        .suggests(SUGGEST_CAPES)
                                        .executes(ctx -> applyCapeByName(ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                        )
        );
    }

    // =========================================================
    // 5) Apply logic (Skin)
    // =========================================================
    private static int applyFromName(FabricClientCommandSource source, String targetNameRaw) {
        MinecraftClient client = source.getClient();
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        if (nh == null) {
            emitError(source, Msg.errNotConnected());
            return 0;
        }

        final String targetName = targetNameRaw == null ? "" : targetNameRaw.trim();
        if (targetName.isEmpty()) {
            emitError(source, Msg.errEmptyName());
            return 0;
        }

        long reqId = REQ_SEQ.incrementAndGet();
        ACTIVE_REQ.set(reqId);

        // 1) TAB
        PlayerListEntry entry = nh.getPlayerListEntry(targetName);
        if (entry == null) entry = findEntryIgnoreCase(nh, targetName);
        if (entry != null && entry.getProfile() != null) {
            GameProfile profile = entry.getProfile();
            String profileName = safeName(profile.getName(), targetName);
            emitStep(source, Msg.stepFromTab(profileName));
            fetchAndApply(client, source, reqId, profileName, profile, SourceTag.TAB);
            return 1;
        }

        // 2) API: name -> uuid -> session textures
        emitStep(source, Msg.stepResolveUuid(targetName));
        resolveUuidFromMojangAsync(targetName).whenComplete((optUuid, err) -> {
            client.execute(() -> {
                if (ACTIVE_REQ.get() != reqId) return;
                if (err != null) {
                    emitError(source, Msg.errUuidResolve(targetName, err));
                    return;
                }
                if (optUuid.isEmpty()) {
                    emitError(source, Msg.errPlayerNotFound(targetName));
                    return;
                }
                UUID uuid = optUuid.get();
                emitStep(source, Msg.stepFetchTextures(targetName));
                fillTexturesFromSessionServerAsync(uuid).whenComplete((optTex, texErr) -> {
                    client.execute(() -> {
                        if (ACTIVE_REQ.get() != reqId) return;
                        if (texErr != null) {
                            emitError(source, Msg.errSessionFetch(targetName, texErr));
                            return;
                        }
                        if (optTex.isEmpty()) {
                            emitError(source, Msg.errNoProfileTextures(targetName));
                            return;
                        }
                        SessionTex tex = optTex.get();
                        String resolvedName = safeName(tex.name, targetName);
                        GameProfile profile = new GameProfile(uuid, resolvedName);
                        profile.getProperties().put("textures", tex.texturesProperty);
                        fetchAndApply(client, source, reqId, resolvedName, profile, SourceTag.API);
                    });
                });
            });
        });

        return 1;
    }

    private static void fetchAndApply(MinecraftClient client,
                                      FabricClientCommandSource source,
                                      long reqId,
                                      String displayName,
                                      GameProfile profile,
                                      SourceTag sourceTag) {
        client.getSkinProvider().fetchSkinTextures(profile).whenComplete((opt, err) -> {
            client.execute(() -> {
                if (ACTIVE_REQ.get() != reqId) return;
                if (err != null) {
                    emitError(source, Msg.errSkinFetch(displayName, err));
                    return;
                }
                if (opt == null || opt.isEmpty()) {
                    emitError(source, Msg.errNoSkinTextures(displayName));
                    return;
                }
                SkinTextures st = opt.get();
                setOverride(displayName, st);
                emitInfo(source, Msg.appliedLine(displayName, st, sourceTag));
                persistSkin(displayName, true);
            });
        });
    }

    private static PlayerListEntry findEntryIgnoreCase(ClientPlayNetworkHandler nh, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (PlayerListEntry e : nh.getPlayerList()) {
            GameProfile p = e.getProfile();
            if (p == null || p.getName() == null) continue;
            if (p.getName().toLowerCase(Locale.ROOT).equals(lower)) return e;
        }
        return null;
    }

    // =========================================================
    // 6) Apply logic (Cape)
    // =========================================================
    private static int applyCapeByName(FabricClientCommandSource source, String capeNameRaw) {
        MinecraftClient client = source.getClient();
        if (client.player == null) {
            emitError(source, Msg.errNotConnected());
            return 0;
        }

        String raw = capeNameRaw == null ? "" : capeNameRaw.trim();
        String key = normalizeCapeKey(raw);
        if (key.isEmpty()) {
            emitError(source, Msg.capeNoFound(raw));
            return 0;
        }

        List<String> urls = BUILTIN_CAPES.get(key);
        if (urls == null || urls.isEmpty()) {
            emitError(source, Msg.capeNoFound(raw));
            return 0;
        }

        // ✅ /cape 요청 레이스 방지: 이번 요청을 최신으로 등록
        long reqId = CAPE_REQ_SEQ.incrementAndGet();
        ACTIVE_CAPE_REQ.set(reqId);

        emitStep(source, Msg.capeFetching(raw));

        downloadDynamicTextureAny(client, urls, "cape_" + key).whenComplete((optId, err) -> {
            client.execute(() -> {
                if (ACTIVE_CAPE_REQ.get() != reqId) return; // ✅ 오래된 요청 무시

                if (err != null || optId.isEmpty()) {
                    emitError(source, Msg.capeNoFound(raw));
                    return;
                }
                Identifier texId = optId.get();
                setCapeOverride(raw, texId, texId);
                emitInfo(source, Msg.capeApplied(raw));

                // ✅ Persist는 raw가 아니라 canonical key 저장
                persistCape(key, true);
            });
        });

        return 1;
    }

    private static CompletableFuture<Optional<Identifier>> downloadDynamicTextureAny(MinecraftClient client, List<String> urls, String keyBase) {
        CompletableFuture<Optional<Identifier>> f = CompletableFuture.completedFuture(Optional.empty());
        for (int i = 0; i < urls.size(); i++) {
            final int idx = i;
            final String url = urls.get(i);
            final String key = keyBase + "_" + (idx + 1);
            f = f.thenCompose(opt -> {
                if (opt.isPresent()) return CompletableFuture.completedFuture(opt);
                return downloadDynamicTexture(client, url, key);
            });
        }
        return f;
    }

    private static CompletableFuture<Optional<Identifier>> downloadDynamicTexture(MinecraftClient client, String url, String key) {
        String u = (url == null) ? "" : url.trim();   // ✅ 공백/개행 제거
        if (u.isEmpty()) return CompletableFuture.completedFuture(Optional.empty());

        Identifier cached = DYN_TEX_CACHE.get(u);
        if (cached != null) return CompletableFuture.completedFuture(Optional.of(cached));

        CompletableFuture<Optional<Identifier>> out = new CompletableFuture<>();
        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(u)) // ✅ u 사용
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "untitledCape/1.0 (Minecraft Fabric)")
                        .header("Accept", "image/png,image/*;q=0.8,*/*;q=0.5")
                        .GET()
                        .build();

                HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) {
                    out.complete(Optional.empty());
                    return;
                }

                byte[] bytes = res.body();
                NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
                NativeImageBackedTexture tex = new NativeImageBackedTexture(img);

                client.execute(() -> {
                    try {
                        Identifier id = makeDynId(key, u); // ✅ u 사용
                        client.getTextureManager().registerTexture(id, tex);
                        DYN_TEX_CACHE.put(u, id);          // ✅ u 사용
                        out.complete(Optional.of(id));
                    } catch (Throwable t) {
                        out.complete(Optional.empty());
                    }
                });
            } catch (Throwable t) {
                out.complete(Optional.empty());
            }
        });
        return out;
    }

    private static Identifier makeDynId(String key, String url) {
        String safeKey = sanitizePath(key);
        String h = Integer.toUnsignedString(url.hashCode(), 16);
        String path = "dyn/" + safeKey + "_" + h;
        return Identifier.of("churrrrr", path);
    }

    private static String sanitizePath(String s) {
        if (s == null || s.isEmpty()) return "x";
        String in = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            boolean ok =
                    (c >= 'a' && c <= 'z') ||
                            (c >= '0' && c <= '9') ||
                            c == '_' || c == '-' || c == '.' || c == '/';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    private static String normalizeCapeKey(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
        }
        return sb.toString();
    }

    private static String prettyName(String keyLower) {
        if (keyLower == null || keyLower.isEmpty()) return keyLower;
        return keyLower.substring(0, 1).toUpperCase(Locale.ROOT) + keyLower.substring(1);
    }

    // =========================================================
    // 7) Mojang UUID resolve
    // =========================================================
    private static CompletableFuture<Optional<UUID>> resolveUuidFromMojangAsync(String nameRaw) {
        final String name = nameRaw.trim();
        final String key = name.toLowerCase(Locale.ROOT);

        long now = System.currentTimeMillis();
        UuidCache cached = UUID_CACHE.get(key);
        if (cached != null && cached.expiresAtMs > now) {
            return CompletableFuture.completedFuture(cached.uuidOpt);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long now2 = System.currentTimeMillis();
                UuidCache cached2 = UUID_CACHE.get(key);
                if (cached2 != null && cached2.expiresAtMs > now2) {
                    return cached2.uuidOpt;
                }

                String enc = URLEncoder.encode(name, StandardCharsets.UTF_8);
                URI uri = URI.create("https://api.mojang.com/users/profiles/minecraft/" + enc);

                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(8))
                        .header("Accept", "application/json")
                        .header("User-Agent", "untitledSkinChanger/1.0 (Minecraft Fabric)")
                        .GET()
                        .build();

                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();

                if (code == 200) {
                    JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
                    if (!obj.has("id")) {
                        putUuidCache(key, Optional.empty(), System.currentTimeMillis() + UUID_CACHE_NEG_MS);
                        return Optional.empty();
                    }
                    UUID uuid = uuidFromMojangId(obj.get("id").getAsString());
                    if (uuid == null) {
                        putUuidCache(key, Optional.empty(), System.currentTimeMillis() + UUID_CACHE_NEG_MS);
                        return Optional.empty();
                    }
                    Optional<UUID> out = Optional.of(uuid);
                    putUuidCache(key, out, System.currentTimeMillis() + UUID_CACHE_OK_MS);
                    return out;
                }

                if (code == 204 || code == 404) {
                    putUuidCache(key, Optional.empty(), System.currentTimeMillis() + UUID_CACHE_NEG_MS);
                    return Optional.empty();
                }

                putUuidCache(key, Optional.empty(), System.currentTimeMillis() + UUID_CACHE_NEG_MS);
                return Optional.empty();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void putUuidCache(String keyLower, Optional<UUID> uuidOpt, long expiresAtMs) {
        UUID_CACHE.put(keyLower, new UuidCache(uuidOpt, expiresAtMs));
    }

    private static UUID uuidFromMojangId(String idNoDash) {
        String s = idNoDash == null ? "" : idNoDash.replace("-", "").trim();
        if (s.length() != 32) return null;

        String dashed = s.substring(0, 8) + "-" +
                s.substring(8, 12) + "-" +
                s.substring(12, 16) + "-" +
                s.substring(16, 20) + "-" +
                s.substring(20, 32);

        return UUID.fromString(dashed);
    }

    private record UuidCache(Optional<UUID> uuidOpt, long expiresAtMs) {}

    // =========================================================
    // 8) SessionServer textures fill
    // =========================================================
    private static CompletableFuture<Optional<SessionTex>> fillTexturesFromSessionServerAsync(UUID uuid) {
        long now = System.currentTimeMillis();
        TexCache cached = TEX_CACHE.get(uuid);
        if (cached != null && cached.expiresAtMs > now) {
            return CompletableFuture.completedFuture(cached.texOpt);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long now2 = System.currentTimeMillis();
                TexCache cached2 = TEX_CACHE.get(uuid);
                if (cached2 != null && cached2.expiresAtMs > now2) {
                    return cached2.texOpt;
                }

                String noDash = uuid.toString().replace("-", "");
                URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + noDash + "?unsigned=false");

                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(8))
                        .header("Accept", "application/json")
                        .header("User-Agent", "untitledSkinChanger/1.0 (Minecraft Fabric)")
                        .GET()
                        .build();

                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                int code = res.statusCode();

                if (code == 200) {
                    JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
                    String name = obj.has("name") ? obj.get("name").getAsString() : null;

                    if (!obj.has("properties")) {
                        putTexCache(uuid, Optional.empty(), System.currentTimeMillis() + TEX_CACHE_NEG_MS);
                        return Optional.empty();
                    }

                    JsonArray props = obj.getAsJsonArray("properties");
                    Property texturesProp = null;

                    for (int i = 0; i < props.size(); i++) {
                        JsonObject p = props.get(i).getAsJsonObject();
                        if (!p.has("name") || !"textures".equals(p.get("name").getAsString())) continue;
                        if (!p.has("value")) continue;

                        String value = p.get("value").getAsString();
                        String sig = p.has("signature") ? p.get("signature").getAsString() : null;

                        texturesProp = (sig != null && !sig.isEmpty())
                                ? new Property("textures", value, sig)
                                : new Property("textures", value);
                        break;
                    }

                    if (texturesProp == null) {
                        putTexCache(uuid, Optional.empty(), System.currentTimeMillis() + TEX_CACHE_NEG_MS);
                        return Optional.empty();
                    }

                    Optional<SessionTex> out = Optional.of(new SessionTex(name, texturesProp));
                    putTexCache(uuid, out, System.currentTimeMillis() + TEX_CACHE_OK_MS);
                    return out;
                }

                putTexCache(uuid, Optional.empty(), System.currentTimeMillis() + TEX_CACHE_NEG_MS);
                return Optional.empty();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void putTexCache(UUID uuid, Optional<SessionTex> texOpt, long expiresAtMs) {
        TEX_CACHE.put(uuid, new TexCache(texOpt, expiresAtMs));
    }

    private record TexCache(Optional<SessionTex> texOpt, long expiresAtMs) {}
    private record SessionTex(String name, Property texturesProperty) {}

    // =========================================================
    // 9) Override storage
    // =========================================================
    private static void setOverride(String fromName, SkinTextures textures) {
        OVERRIDE_FROM.set(fromName);
        OVERRIDE.set(textures);
    }

    private static void setCapeOverride(String fromName, Identifier capeTex, Identifier elytraTex) {
        CAPE_FROM.set(fromName);
        CAPE_OVERRIDE.set(capeTex);
        ELYTRA_OVERRIDE.set(elytraTex);
    }

    private static void clearOverride() {
        ACTIVE_REQ.set(0L);
        OVERRIDE_FROM.set(null);
        OVERRIDE.set(null);
    }

    private static void clearCapeOverride() {
        ACTIVE_CAPE_REQ.set(0L);
        CAPE_FROM.set(null);
        CAPE_OVERRIDE.set(null);
        ELYTRA_OVERRIDE.set(null);
    }

    // 남겨둠(현재는 직접 호출 안 함)
    private static void clearAllOverrides() {
        clearOverride();
        clearCapeOverride();
    }

    public static SkinTextures getOverrideSkinTextures() {
        return OVERRIDE.get();
    }

    public static Optional<String> getOverrideFromName() {
        return Optional.ofNullable(OVERRIDE_FROM.get());
    }

    public static Identifier getOverrideCapeTexture() {
        return CAPE_OVERRIDE.get();
    }

    public static Identifier getOverrideElytraTexture() {
        return ELYTRA_OVERRIDE.get();
    }

    public static Optional<String> getCapeFromName() {
        return Optional.ofNullable(CAPE_FROM.get());
    }

    // =========================================================
    // 10) Auto restore
    // =========================================================
    private static void tryAutoRestore(MinecraftClient client) {
        if (AUTO_RESTORE_TRIED) return;
        if (client.player == null) return;
        if (client.getNetworkHandler() == null) return;

        AUTO_RESTORE_TRIED = true;

        Persist p = PERSIST.get();
        if (p == null) return;

        // 스킨 자동 복구
        if (p.skinEnabled && p.skinName != null && !p.skinName.isBlank()) {
            silentApplySkin(client, p.skinName.trim());
        }

        // 케이프 자동 복구
        if (p.capeEnabled && p.capeName != null && !p.capeName.isBlank()) {
            silentApplyCape(client, p.capeName.trim());
        }
    }

    private static void silentApplySkin(MinecraftClient client, String name) {
        ClientPlayNetworkHandler nh = client.getNetworkHandler();
        if (nh == null) return;

        long reqId = REQ_SEQ.incrementAndGet();
        ACTIVE_REQ.set(reqId);

        PlayerListEntry entry = nh.getPlayerListEntry(name);
        if (entry == null) entry = findEntryIgnoreCase(nh, name);
        if (entry != null && entry.getProfile() != null) {
            GameProfile profile = entry.getProfile();
            client.getSkinProvider().fetchSkinTextures(profile).whenComplete((opt, err) -> {
                client.execute(() -> {
                    if (ACTIVE_REQ.get() != reqId) return;
                    if (err != null || opt == null || opt.isEmpty()) return;
                    setOverride(safeName(profile.getName(), name), opt.get());
                });
            });
            return;
        }

        resolveUuidFromMojangAsync(name).whenComplete((optUuid, err) -> {
            client.execute(() -> {
                if (ACTIVE_REQ.get() != reqId) return;
                if (err != null || optUuid.isEmpty()) return;
                UUID uuid = optUuid.get();

                fillTexturesFromSessionServerAsync(uuid).whenComplete((optTex, texErr) -> {
                    client.execute(() -> {
                        if (ACTIVE_REQ.get() != reqId) return;
                        if (texErr != null || optTex.isEmpty()) return;

                        SessionTex tex = optTex.get();
                        String resolvedName = safeName(tex.name, name);

                        GameProfile profile = new GameProfile(uuid, resolvedName);
                        profile.getProperties().put("textures", tex.texturesProperty);

                        client.getSkinProvider().fetchSkinTextures(profile).whenComplete((opt, e2) -> {
                            client.execute(() -> {
                                if (ACTIVE_REQ.get() != reqId) return;
                                if (e2 != null || opt == null || opt.isEmpty()) return;
                                setOverride(resolvedName, opt.get());
                            });
                        });
                    });
                });
            });
        });
    }

    private static void silentApplyCape(MinecraftClient client, String capeName) {
        String raw = capeName == null ? "" : capeName.trim();
        String key = normalizeCapeKey(raw);
        List<String> urls = BUILTIN_CAPES.get(key);
        if (urls == null || urls.isEmpty()) return;

        long reqId = CAPE_REQ_SEQ.incrementAndGet();
        ACTIVE_CAPE_REQ.set(reqId);

        downloadDynamicTextureAny(client, urls, "cape_" + key).whenComplete((optId, err) -> {
            client.execute(() -> {
                if (ACTIVE_CAPE_REQ.get() != reqId) return;
                if (err != null || optId.isEmpty()) return;
                Identifier id = optId.get();

                // ✅ 표시 일관성: 저장값(key)라도 보기 좋게
                String display = prettyName(key);
                setCapeOverride(display, id, id);
            });
        });
    }
    // =========================================================
    // 11) Persist helpers
    // =========================================================
    private static void persistSkin(String nameOrNull, boolean enabled) {
        Persist p = PERSIST.get();
        if (p == null) p = new Persist();

        p.skinEnabled = enabled;
        p.skinName = enabled ? nameOrNull : null;

        PERSIST.set(p);
        p.save(COSMETIC_CFG);
    }

    private static void persistCape(String nameOrNull, boolean enabled) {
        Persist p = PERSIST.get();
        if (p == null) p = new Persist();

        p.capeEnabled = enabled;
        p.capeName = enabled ? nameOrNull : null;

        PERSIST.set(p);
        p.save(COSMETIC_CFG);
    }

    private static final class Persist {
        boolean skinEnabled = false;
        String skinName = null;

        boolean capeEnabled = false;
        String capeName = null;

        static Persist load(Path path) {
            try {
                if (!Files.exists(path)) return new Persist();
                String s = Files.readString(path);
                JsonObject o = JsonParser.parseString(s).getAsJsonObject();

                Persist p = new Persist();
                if (o.has("skinEnabled")) p.skinEnabled = o.get("skinEnabled").getAsBoolean();
                if (o.has("skinName")) p.skinName = o.get("skinName").getAsString();
                if (o.has("capeEnabled")) p.capeEnabled = o.get("capeEnabled").getAsBoolean();
                if (o.has("capeName")) p.capeName = o.get("capeName").getAsString();

                if (!p.skinEnabled) p.skinName = null;
                if (!p.capeEnabled) p.capeName = null;

                return p;
            } catch (Throwable t) {
                return new Persist();
            }
        }

        void save(Path path) {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("skinEnabled", skinEnabled);
                if (skinName != null) o.addProperty("skinName", skinName);

                o.addProperty("capeEnabled", capeEnabled);
                if (capeName != null) o.addProperty("capeName", capeName);

                Files.writeString(path, o.toString());
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================
    // 12) Message system
    // =========================================================
    private static void emitInfo(FabricClientCommandSource src, MutableText line) {
        src.sendFeedback(skinHeader().append(line));
    }

    private static void emitError(FabricClientCommandSource src, MutableText line) {
        src.sendError(skinHeader().append(line));
    }

    private static void emitStep(FabricClientCommandSource src, MutableText line) {
        if (!SHOW_STEPS) return;
        src.sendFeedback(skinHeader().append(line));
    }

    private static final class Msg {
        private static final String SYM_STEP = "… ";
        private static final String SYM_OK = "✓ ";
        private static final String SYM_ERR = "✖ ";

        private static MutableText usageLine() {
            return gray("Usage: ")
                    .append(whiteBold("/skin <player>"))
                    .append(gray("  |  "))
                    .append(whiteBold("/skin reset"));
        }

        private static MutableText capeUsageLine() {
            return gray("Usage: ")
                    .append(whiteBold("/cape <name>"))
                    .append(gray("  |  "))
                    .append(whiteBold("/cape reset"));
        }

        // ✅ /skin reset 전용
        private static MutableText skinResetLine() {
            return bad(SYM_OK).append(gray("Skin "))
                    .append(bad("→ "))
                    .append(bad("RESET"))
                    .append(dim(" (cleared)"));
        }

        // ✅ /cape reset 전용
        private static MutableText capeResetLine() {
            return bad(SYM_OK).append(gray("Cape "))
                    .append(bad("→ "))
                    .append(bad("RESET"))
                    .append(dim(" (cleared)"));
        }

        // (남겨둠 - 현재는 호출 안 함)
        private static MutableText overrideResetLine() {
            return bad(SYM_OK).append(gray("Override "))
                    .append(bad("→ "))
                    .append(bad("RESET"))
                    .append(dim(" (cleared)"));
        }

        private static MutableText errNotConnected() {
            return bad(SYM_ERR + "Not connected")
                    .append(dim(" (enter a world/server)"));
        }

        private static MutableText errEmptyName() {
            return bad(SYM_ERR + "Empty name")
                    .append(dim(" (try /skin <player>)"));
        }

        private static MutableText stepFromTab(String name) {
            return dim(SYM_STEP + "Source ")
                    .append(tag(SourceTag.TAB))
                    .append(dim(" → "))
                    .append(whiteBold(name));
        }

        private static MutableText stepResolveUuid(String name) {
            return dim(SYM_STEP + "Lookup UUID ")
                    .append(tag(SourceTag.API))
                    .append(dim(" → "))
                    .append(whiteBold(name));
        }

        private static MutableText stepFetchTextures(String name) {
            return dim(SYM_STEP + "Fetching textures ")
                    .append(tag(SourceTag.API))
                    .append(dim(" → "))
                    .append(whiteBold(name));
        }

        private static MutableText errUuidResolve(String name, Throwable err) {
            return bad(SYM_ERR + "UUID lookup failed")
                    .append(dim(" → "))
                    .append(whiteBold(name))
                    .append(dim("  ("))
                    .append(bad(err.getClass().getSimpleName()))
                    .append(dim(")"));
        }

        private static MutableText errPlayerNotFound(String name) {
            return bad(SYM_ERR + "Player not found")
                    .append(dim(" → "))
                    .append(whiteBold(name));
        }

        private static MutableText errSessionFetch(String name, Throwable err) {
            return bad(SYM_ERR + "Profile fetch failed")
                    .append(dim(" → "))
                    .append(whiteBold(name))
                    .append(dim("  ("))
                    .append(bad(err.getClass().getSimpleName()))
                    .append(dim(")"));
        }

        private static MutableText errNoProfileTextures(String name) {
            return bad(SYM_ERR + "No profile textures")
                    .append(dim(" → "))
                    .append(whiteBold(name))
                    .append(dim("  (rate-limit / invalid / offline-mode?)"));
        }

        private static MutableText errSkinFetch(String name, Throwable err) {
            return bad(SYM_ERR + "Skin fetch failed")
                    .append(dim(" → "))
                    .append(whiteBold(name))
                    .append(dim("  ("))
                    .append(bad(err.getClass().getSimpleName()))
                    .append(dim(")"));
        }

        private static MutableText errNoSkinTextures(String name) {
            return bad(SYM_ERR + "No skin textures")
                    .append(dim(" → "))
                    .append(whiteBold(name));
        }

        private static MutableText appliedLine(String name, SkinTextures st, SourceTag src) {
            String model = (st.model() == SkinTextures.Model.SLIM) ? "SLIM" : "WIDE";
            MutableText out = ok(SYM_OK + "Applied ")
                    .append(ok("→ "))
                    .append(ok(name));
            if (SHOW_SOURCE_TAG) {
                out.append(dim(" ")).append(tag(src));
            }
            out.append(dim(" [" + model + "]"));
            return out;
        }

        private static MutableText capeFetching(String name) {
            return dim(SYM_STEP + "Injecting cape → ")
                    .append(whiteBold(name));
        }

        private static MutableText capeApplied(String name) {
            return ok(SYM_OK + "Cape ")
                    .append(ok("→ "))
                    .append(ok(name));
        }

        private static MutableText capeNoFound(String name) {
            return bad(SYM_ERR + "No cape found")
                    .append(dim(" → "))
                    .append(whiteBold(name));
        }

        private static MutableText tag(SourceTag src) {
            int rgb = (src == SourceTag.TAB) ? 0xC0FFFF : 0xFFE4FF;
            String txt = (src == SourceTag.TAB) ? "[TAB]" : "[API]";
            return Text.literal(txt).styled(s -> s.withColor(TextColor.fromRgb(rgb)).withBold(true));
        }
    }

    // =========================================================
    // 13) Header + Text helpers
    // =========================================================
    private static MutableText skinHeader() {
        MutableText out = Text.empty();
        out.append(Text.literal("[")
                .styled(s -> s.withColor(TextColor.fromRgb(0x666666))));
        out.append(gradient("untitled", 0xFFE4FF, 0xC0FFFF, true));
        out.append(Text.literal("] ")
                .styled(s -> s.withColor(TextColor.fromRgb(0x666666))));
        out.append(Text.literal("Skin ")
                .styled(s -> s.withColor(TextColor.fromRgb(0xFFFFFF)).withBold(true)));
        out.append(Text.literal("• ")
                .styled(s -> s.withColor(TextColor.fromRgb(0x777777))));
        return out;
    }

    private static MutableText gradient(String s, int fromRgb, int toRgb, boolean bold) {
        MutableText out = Text.empty();
        if (s == null || s.isEmpty()) return out;
        int len = s.length();
        if (len == 1) {
            return Text.literal(s).styled(st -> st.withColor(TextColor.fromRgb(fromRgb)).withBold(bold));
        }
        for (int i = 0; i < len; i++) {
            float t = i / (float) (len - 1);
            int r = (int) (((fromRgb >> 16) & 0xFF) * (1 - t) + ((toRgb >> 16) & 0xFF) * t);
            int g = (int) (((fromRgb >> 8) & 0xFF) * (1 - t) + ((toRgb >> 8) & 0xFF) * t);
            int b = (int) (((fromRgb) & 0xFF) * (1 - t) + ((toRgb) & 0xFF) * t);
            int rgb = (r << 16) | (g << 8) | b;
            out.append(Text.literal(String.valueOf(s.charAt(i)))
                    .styled(st -> st.withColor(TextColor.fromRgb(rgb)).withBold(bold)));
        }
        return out;
    }

    private static MutableText gray(String s) {
        return Text.literal(s).styled(st -> st.withColor(TextColor.fromRgb(0xAAAAAA)));
    }

    private static MutableText dim(String s) {
        return Text.literal(s).styled(st -> st.withColor(TextColor.fromRgb(0x777777)));
    }

    private static MutableText whiteBold(String s) {
        return Text.literal(s).styled(st -> st.withColor(TextColor.fromRgb(0xFFFFFF)).withBold(true));
    }

    private static MutableText ok(String s) {
        return Text.literal(s).styled(st -> st.withColor(TextColor.fromRgb(0x9CFF6F)).withBold(true));
    }

    private static MutableText bad(String s) {
        return Text.literal(s).styled(st -> st.withColor(TextColor.fromRgb(0xFF5577)).withBold(true));
    }

    private static String safeName(String maybeNull, String fallback) {
        return (maybeNull == null || maybeNull.isEmpty()) ? fallback : maybeNull;
    }
}