package untitled.untitled.client;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class KillEffect {
    private KillEffect() {}

    // ===== Config =====
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("152_killeffect.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int EFFECT_NAME_COLOR = 0xD0D0D0;

    // ===== Particle tuning =====
    private static final int TOTEM_PARTICLE_COUNT = 36;
    private static final int WING_PARTICLE_COUNT = 60;
    private static final int POTION_TRAIL_PARTICLES = 2;
    private static final int POTION_SPLASH_PARTICLES = 28;

    private static final double POTION_DROP_HEIGHT = 2.8;
    private static final long POTION_TIMEOUT_MS = 2500;

    // ===== Blood tuning =====
    private static final DustParticleEffect BLOOD_BRIGHT = new DustParticleEffect(0xB30D21, 1.35f);
    private static final DustParticleEffect BLOOD_DARK = new DustParticleEffect(0x650611, 1.10f);
    private static final int BLOOD_BURST_PARTICLES = 34;
    private static final int BLOOD_GROUND_PARTICLES = 20;

    // ===== Explosion tuning =====
    private static final int EXPLOSION_RING_PARTICLES = 36;
    private static final int EXPLOSION_SMOKE_PARTICLES = 18;
    private static final double EXPLOSION_RING_RADIUS = 1.55;

    // ===== Ender tuning =====
    private static final DustParticleEffect ENDER_SIMPLE_PURPLE = new DustParticleEffect(0x7137FF, 1.15f);
    private static final DustParticleEffect ENDER_SIMPLE_DARK = new DustParticleEffect(0x21002E, 1.25f);
    private static final int ENDER_SIMPLE_PARTICLES = 52;

    // ===== Freeze tuning =====
    private static final DustParticleEffect FREEZE_BRIGHT = new DustParticleEffect(0xD8FAFF, 1.10f);
    private static final DustParticleEffect FREEZE_BLUE = new DustParticleEffect(0x72DFFF, 1.00f);
    private static final int FREEZE_PARTICLES = 42;

    // ===== Void / black-hole tuning =====
    private static final DustParticleEffect VOID_PURPLE = new DustParticleEffect(0x8E44FF, 0.95f);
    private static final DustParticleEffect VOID_DARK = new DustParticleEffect(0x17001F, 1.20f);
    private static final long VOID_DURATION_MS = 650;
    private static final int VOID_PARTICLES_PER_TICK = 24;
    private static final double VOID_MAX_RADIUS = 2.15;
    private static int voidSeq = 1;

    // ===== Rose tuning =====
    private static final DustParticleEffect ROSE_PINK = new DustParticleEffect(0xFF5A9D, 1.10f);
    private static final DustParticleEffect ROSE_RED = new DustParticleEffect(0xC71952, 0.95f);
    private static final DustParticleEffect ROSE_GLOW = new DustParticleEffect(0xFFD6EA, 0.85f);
    private static final int ROSE_PARTICLES = 72;
    private static final int ROSE_RING_PARTICLES = 36;
    private static final int ROSE_FALLING_PETALS = 24;

    // ===== Soul / glitch tuning =====
    private static final long GLITCH_DURATION_MS = 200;
    private static final int GLITCH_PARTICLES_PER_TICK = 26;
    private static final double GLITCH_RADIUS = 0.85;
    private static int glitchSeq = 1;

    private enum EffectType {
        THUNDER, TOTEM, SOUL, EXPLOSION, WING, POTION, ROSE, FIREWORK, HEAD, SWORD, SNOW, NOTE;
        static EffectType parse(String s) {
            if (s == null) return null;
            String q = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (q.equals("glitch")) return SOUL;
            if (q.equals("tnt")) return EXPLOSION;
            try { return EffectType.valueOf(q.toUpperCase(java.util.Locale.ROOT)); }
            catch (IllegalArgumentException e) { return null; }
        }
        String id() { return name().toLowerCase(java.util.Locale.ROOT); }
        String displayName() { return name().substring(0, 1) + name().substring(1).toLowerCase(java.util.Locale.ROOT); }
    }

    private static boolean enabled = false;
    private static EffectType effect = EffectType.THUNDER;

    // ===== Player-only kill heuristic tracking =====
    private static final long TRACK_TIMEOUT_MS = 1000;

    // UUID is stable across entity replacement/respawn, unlike a runtime entity id.
    // Only one active Track can exist for the same player at a time.
    private static final Map<UUID, Track> tracks = new HashMap<>();

    // Once a kill effect fires, keep that player locked until an actually alive
    // PlayerEntity with the same UUID is observed in the current world again.
    // This prevents rapid attack packets/events around the death transition from
    // creating another Track and firing the same kill effect more than once.
    private static final Set<UUID> killLockedPlayers = new HashSet<>();

    private static final class Track {
        final UUID playerUuid;
        int entityId;
        long lastSeenMs;
        Vec3d lastPos;
        String playerName;

        Track(UUID playerUuid, int entityId, long nowMs, Vec3d pos, String name) {
            this.playerUuid = playerUuid;
            this.entityId = entityId;
            this.lastSeenMs = nowMs;
            this.lastPos = pos;
            this.playerName = name;
        }
    }

    // ===== Potion drop tracking =====
    private static final Map<Integer, PotionDrop> potionDrops = new HashMap<>();

    private static final class PotionDrop {
        final int entityId;
        final Vec3d targetPos;
        final long spawnMs;
        final double fallSpeed;
        final double driftX;
        final double driftZ;

        PotionDrop(int entityId, Vec3d targetPos, long spawnMs, double fallSpeed, double driftX, double driftZ) {
            this.entityId = entityId;
            this.targetPos = targetPos;
            this.spawnMs = spawnMs;
            this.fallSpeed = fallSpeed;
            this.driftX = driftX;
            this.driftZ = driftZ;
        }
    }

    // ===== Soul burst tracking =====
    private static final Map<Integer, GlitchBurst> glitchBursts = new HashMap<>();

    private static final class GlitchBurst {
        final int id;
        final Vec3d basePos;
        final long startMs;
        final long endMs;
        long state;
        int tick;

        GlitchBurst(int id, Vec3d basePos, long startMs, long endMs, long seed) {
            this.id = id;
            this.basePos = basePos;
            this.startMs = startMs;
            this.endMs = endMs;
            this.state = seed;
            this.tick = 0;
        }
    }

    // ===== Void black-hole tracking =====
    private static final Map<Integer, VoidBurst> voidBursts = new HashMap<>();

    private static final class VoidBurst {
        final int id;
        final Vec3d basePos;
        final long startMs;
        final long endMs;
        int tick;

        VoidBurst(int id, Vec3d basePos, long startMs, long endMs) {
            this.id = id;
            this.basePos = basePos;
            this.startMs = startMs;
            this.endMs = endMs;
            this.tick = 0;
        }
    }

    // ===== GUI control API =====
    public static boolean settingsEnabled() { return enabled; }
    public static String settingsEffect() { return effect.displayName(); }
    public static java.util.List<String> settingsEffects() {
        return java.util.Arrays.stream(EffectType.values()).map(EffectType::displayName).toList();
    }
    public static void toggleSettingsEnabled() {
        enabled = !enabled;
        if (!enabled) { tracks.clear(); killLockedPlayers.clear(); }
        saveConfig();
    }
    public static boolean setSettingsEffect(String name) {
        EffectType selected = EffectType.parse(name);
        if (selected == null) return false;
        effect = selected;
        saveConfig();
        return true;
    }
    public static void previewSettings() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null || mc.player == null) return;
        Vec3d pos = mc.player.getPos().add(mc.player.getRotationVec(1.0f).multiply(2.5));
        trigger(effect, mc.world, pos, System.currentTimeMillis(), mc.player.getUuid(), mc.player.getGameProfile().getName());
    }

    // ===== Public API =====
    public static void register() {
        loadConfig();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = ClientCommandManager.literal("ke")
                .executes(ctx -> { send(statusLine()); return 1; })
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    toggleSettingsEnabled(); send(statusLine()); return 1;
                }))
                .then(ClientCommandManager.literal("preview").executes(ctx -> {
                    previewSettings(); return 1;
                }));
            for (EffectType kind : EffectType.values()) {
                root.then(ClientCommandManager.literal(kind.id()).executes(ctx -> {
                    setSettingsEffect(kind.id()); send(statusLine()); return 1;
                }));
            }
            dispatcher.register(root);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.world == null) return;
            tick(client, System.currentTimeMillis());
        });
    }

    /** Called by the interaction-manager mixin when the local player attacks an entity. */
    public static void onLocalAttack(Entity target) {
        if (!enabled) return;
        if (!(target instanceof PlayerEntity player)) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return;
        if (mc.player == player) return;

        UUID uuid = player.getUuid();
        int id = player.getId();
        long now = System.currentTimeMillis();

        // A player that already produced an effect for the current death stays
        // locked. Unlock only when the current world contains a live PlayerEntity
        // for that UUID again (normally the next life/respawn). A stale target
        // object from the death transition therefore cannot unlock itself.
        if (killLockedPlayers.contains(uuid)) {
            Entity current = mc.world.getEntityById(id);
            boolean aliveAgain = current instanceof PlayerEntity currentPlayer
                    && currentPlayer.getUuid().equals(uuid)
                    && currentPlayer.isAlive();
            if (!aliveAgain) return;
            killLockedPlayers.remove(uuid);
        }

        Vec3d pos = player.getPos();
        Track t = tracks.get(uuid);
        if (t == null) {
            tracks.put(uuid, new Track(uuid, id, now, pos, player.getGameProfile().getName()));
        } else {
            // Keep one Track per player while refreshing the latest runtime id
            // and position in case the server replaces the client-side entity.
            t.entityId = id;
            t.lastSeenMs = now;
            t.lastPos = pos;
            t.playerName = player.getGameProfile().getName();
        }
    }

    // ===== Tick logic =====
    private static void tick(MinecraftClient client, long nowMs) {
        ClientWorld world = client.world;
        if (world == null) return;

        tickGlitchBursts(world, nowMs);
        tickVoidBursts(world, nowMs);
        tickPotionDrops(world, nowMs);
        tickFireworks(world, nowMs);
        tickHeadDrops(world, nowMs);
        SwordKillEffect.tick(world, nowMs);
        SnowKillEffect.tick(world, nowMs);
        NoteKillEffect.tick(world, nowMs);

        if (tracks.isEmpty()) return;

        Iterator<Map.Entry<UUID, Track>> it = tracks.entrySet().iterator();
        while (it.hasNext()) {
            Track tr = it.next().getValue();

            if (nowMs - tr.lastSeenMs > TRACK_TIMEOUT_MS) {
                it.remove();
                continue;
            }

            Entity e = world.getEntityById(tr.entityId);
            boolean dead;

            if (e == null) {
                // Preserve the existing server-compatible heuristic: disappearance
                // within the tracking window counts as the death transition.
                dead = true;
            } else if (e instanceof PlayerEntity player
                    && player.getUuid().equals(tr.playerUuid)) {
                tr.lastPos = player.getPos();
                dead = !player.isAlive();
            } else {
                // Runtime entity ids may be reused. Never let another entity or
                // another player inherit this player's pending kill track.
                it.remove();
                continue;
            }

            if (dead) {
                // Set.add() is the single-fire gate. Even if several attack/death
                // observations overlap, only the first transition for this UUID
                // can produce a kill effect until the player is alive again.
                if (enabled && tr.lastPos != null && killLockedPlayers.add(tr.playerUuid)) {
                    trigger(effect, world, tr.lastPos, nowMs, tr.playerUuid, tr.playerName);
                }
                it.remove();
            }
        }
    }

    // Client-side fireworks: rising trail followed by a randomized colored burst.
    private static final java.util.List<FireworkBurst> fireworkBursts = new java.util.ArrayList<>();
    private static final class FireworkBurst {
        final Vec3d pos;
        final long start;
        boolean exploded;
        FireworkBurst(Vec3d pos, long start) { this.pos = pos; this.start = start; }
    }
    private static void tickFireworks(ClientWorld world, long now) {
        java.util.Iterator<FireworkBurst> it = fireworkBursts.iterator();
        while (it.hasNext()) {
            FireworkBurst rocket = it.next();
            long age = now - rocket.start;
            if (age < 0) { it.remove(); continue; }
            double progress = Math.min(1.0, age / 550.0);
            Vec3d p = rocket.pos.add(0, 0.7 + progress * 3.1, 0);
            if (age < 550) {
                world.addParticle(ParticleTypes.FIREWORK, p.x, p.y, p.z, 0, -0.025, 0);
            } else if (!rocket.exploded) {
                rocket.exploded = true;
                java.util.Random random = new java.util.Random();
                int[] palette = {0xFF4466, 0xFFDD44, 0x44CCFF, 0x88FF66, 0xCC77FF, 0xFF88DD};
                int rgb = palette[random.nextInt(palette.length)];
                DustParticleEffect dust = new DustParticleEffect(rgb, 1.25f);
                for (int i = 0; i < 85; i++) {
                    double angle = random.nextDouble() * Math.PI * 2;
                    double z = random.nextDouble() * 2 - 1;
                    double spread = Math.sqrt(1 - z * z);
                    double speed = 0.12 + random.nextDouble() * 0.28;
                    world.addParticle(i % 5 == 0 ? ParticleTypes.FIREWORK : dust,
                        p.x, p.y, p.z, Math.cos(angle)*spread*speed, z*speed,
                        Math.sin(angle)*spread*speed);
                }
                world.playSound(p.x,p.y,p.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST,
                    SoundCategory.PLAYERS, 0.9f, 1.05f, false);
            }
            if (age > 850) it.remove();
        }
    }

    private static void spawnCherryRose(ClientWorld world, Vec3d pos) {
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 48; i++) {
            double a = random.nextDouble() * Math.PI * 2;
            double radius = random.nextDouble() * 1.3;
            world.addParticle(ParticleTypes.CHERRY_LEAVES,
                pos.x + Math.cos(a)*radius, pos.y + 0.8 + random.nextDouble()*2.6,
                pos.z + Math.sin(a)*radius, -Math.sin(a)*0.025,
                -0.035-random.nextDouble()*0.055, Math.cos(a)*0.025);
        }
        world.playSound(pos.x,pos.y,pos.z, SoundEvents.BLOCK_CHERRY_LEAVES_BREAK,
            SoundCategory.PLAYERS, 1.0f, 1.4f, false);
        world.playSound(pos.x,pos.y,pos.z, SoundEvents.ENTITY_PLAYER_LEVELUP,
            SoundCategory.PLAYERS, 1.2f, 1.65f, false);
    }

    private static final java.util.List<HeadDrop> headDrops = new java.util.ArrayList<>();
    private static final class HeadDrop {
        final ClientWorld world;
        final net.minecraft.entity.ItemEntity entity;
        final long expires;
        HeadDrop(ClientWorld world, net.minecraft.entity.ItemEntity entity, long expires) {
            this.world = world; this.entity = entity; this.expires = expires;
        }
    }
    private static void tickHeadDrops(ClientWorld world, long now) {
        var it = headDrops.iterator();
        while (it.hasNext()) {
            HeadDrop drop = it.next();
            if (drop.world != world || now >= drop.expires || drop.entity.isRemoved()) {
                if (!drop.entity.isRemoved()) drop.entity.discard();
                it.remove();
            }
        }
    }
    private static void spawnHead(ClientWorld world, Vec3d pos, UUID uuid, String name) {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        if (uuid != null && name != null) {
            head.set(net.minecraft.component.DataComponentTypes.PROFILE,
                new net.minecraft.component.type.ProfileComponent(new com.mojang.authlib.GameProfile(uuid, name)));
        }
        // Cosmetic local-only item entity, automatically removed; never creates server loot.
        net.minecraft.entity.ItemEntity drop = new net.minecraft.entity.ItemEntity(
            world, pos.x, pos.y + 1.25, pos.z, head);
        drop.setVelocity((Math.random() - 0.5) * 0.16, 0.24, (Math.random() - 0.5) * 0.16);
        drop.setPickupDelayInfinite();
        world.addEntity(drop);
        headDrops.add(new HeadDrop(world, drop, System.currentTimeMillis() + 2400L));
        world.playSound(pos.x, pos.y, pos.z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT,
            SoundCategory.PLAYERS, 1.2f, 0.8f, false);
        world.playSound(pos.x, pos.y, pos.z, SoundEvents.ENTITY_WITHER_SKELETON_DEATH,
            SoundCategory.PLAYERS, 0.9f, 1.2f, false);
    }

    // ===== Effects =====
    private static void trigger(EffectType type, ClientWorld world, Vec3d pos, long nowMs, UUID playerId, String playerName) {
        if (type == null) return;

        if (type == EffectType.THUNDER) spawnThunder(world, pos);
        else if (type == EffectType.TOTEM) spawnTotem(world, pos);
        else if (type == EffectType.WING) spawnWing(world, pos);
        else if (type == EffectType.POTION) spawnPotionDrop(world, pos, nowMs);
        else if (type == EffectType.SOUL) spawnSoul(world, pos, nowMs);
        else if (type == EffectType.EXPLOSION) spawnTnt(world, pos);
        else if (type == EffectType.ROSE) spawnCherryRose(world, pos);
        else if (type == EffectType.FIREWORK) fireworkBursts.add(new FireworkBurst(pos, nowMs));
        else if (type == EffectType.HEAD) spawnHead(world, pos, playerId, playerName);
        else if (type == EffectType.SWORD) SwordKillEffect.spawn(world, pos, nowMs);
        else if (type == EffectType.SNOW) SnowKillEffect.spawn(world, pos, nowMs);
        else if (type == EffectType.NOTE) NoteKillEffect.spawn(world, nowMs);
    }

    private static void spawnEnder(ClientWorld world, Vec3d pos) {
        Vec3d center = new Vec3d(pos.x, pos.y + 1.0, pos.z);

        for (int i = 0; i < ENDER_SIMPLE_PARTICLES; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double radius = 0.55 + Math.random() * 1.15;
            double height = (Math.random() - 0.5) * 1.65;
            double px = center.x + Math.cos(angle) * radius;
            double py = center.y + height;
            double pz = center.z + Math.sin(angle) * radius;

            Vec3d inward = center.subtract(new Vec3d(px, py, pz));
            if (inward.lengthSquared() > 1.0E-6) inward = inward.normalize();
            double speed = 0.08 + Math.random() * 0.16;

            ParticleEffect p;
            if (i % 5 == 0) p = ParticleTypes.REVERSE_PORTAL;
            else if (i % 3 == 0) p = ENDER_SIMPLE_DARK;
            else p = ENDER_SIMPLE_PURPLE;

            world.addParticle(p, px, py, pz, inward.x * speed, inward.y * speed, inward.z * speed);
        }

        for (int i = 0; i < 12; i++) {
            double a = Math.PI * 2.0 * i / 12.0;
            world.addParticle(
                    ParticleTypes.WITCH,
                    center.x + Math.cos(a) * 0.28,
                    center.y + (Math.random() - 0.5) * 0.30,
                    center.z + Math.sin(a) * 0.28,
                    -Math.cos(a) * 0.035,
                    0.01,
                    -Math.sin(a) * 0.035
            );
        }

        SoundEvent s = soundOfByIdFallback("entity.enderman.teleport", SoundEvents.ENTITY_ENDERMAN_TELEPORT);
        if (s != null) world.playSound(center.x, center.y, center.z, s, SoundCategory.PLAYERS, 1.15f, 0.62f, false);
    }

    private static void spawnFreeze(ClientWorld world, Vec3d pos) {
        double x = pos.x;
        double y = pos.y + 0.95;
        double z = pos.z;

        for (int i = 0; i < FREEZE_PARTICLES; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double speed = 0.10 + Math.random() * 0.28;
            double vx = Math.cos(angle) * speed;
            double vz = Math.sin(angle) * speed;
            double vy = -0.02 + Math.random() * 0.30;

            ParticleEffect p;
            if (i % 4 == 0) p = ParticleTypes.SNOWFLAKE;
            else if (i % 7 == 0) p = ParticleTypes.ITEM_SNOWBALL;
            else p = (i % 2 == 0) ? FREEZE_BRIGHT : FREEZE_BLUE;

            world.addParticle(
                    p,
                    x + (Math.random() - 0.5) * 0.35,
                    y + (Math.random() - 0.5) * 0.65,
                    z + (Math.random() - 0.5) * 0.35,
                    vx, vy, vz
            );
        }

        for (int ring = 0; ring < 2; ring++) {
            double radius = (ring == 0) ? 0.52 : 0.92;
            int count = (ring == 0) ? 18 : 26;
            for (int i = 0; i < count; i++) {
                double a = Math.PI * 2.0 * i / count;
                ParticleEffect dust = (i % 2 == 0) ? FREEZE_BRIGHT : FREEZE_BLUE;
                world.addParticle(dust, x + Math.cos(a) * radius, pos.y + 0.12, z + Math.sin(a) * radius, 0.0, 0.025, 0.0);
            }
        }

        SoundEvent crack = soundOfByIdFallback("block.glass.break", SoundEvents.ENTITY_GENERIC_HURT);
        if (crack != null) world.playSound(x, y, z, crack, SoundCategory.PLAYERS, 1.25f, 1.35f, false);
    }

    private static void spawnVoid(ClientWorld world, Vec3d pos, long nowMs) {
        Vec3d center = new Vec3d(pos.x, pos.y + 0.95, pos.z);
        int id = voidSeq++;
        voidBursts.put(id, new VoidBurst(id, center, nowMs, nowMs + VOID_DURATION_MS));

        SoundEvent s = soundOfByIdFallback("entity.enderman.stare", SoundEvents.ENTITY_ENDERMAN_TELEPORT);
        if (s != null) world.playSound(center.x, center.y, center.z, s, SoundCategory.PLAYERS, 1.0f, 0.55f, false);
        emitVoidFrame(world, voidBursts.get(id), nowMs);
    }

    private static void tickVoidBursts(ClientWorld world, long nowMs) {
        if (voidBursts.isEmpty()) return;

        Iterator<Map.Entry<Integer, VoidBurst>> it = voidBursts.entrySet().iterator();
        while (it.hasNext()) {
            VoidBurst eb = it.next().getValue();
            if (nowMs >= eb.endMs) {
                finishVoid(world, eb);
                it.remove();
                continue;
            }
            emitVoidFrame(world, eb, nowMs);
        }
    }

    private static void emitVoidFrame(ClientWorld world, VoidBurst eb, long nowMs) {
        eb.tick++;
        double t = MathHelper.clamp((nowMs - eb.startMs) / (double) VOID_DURATION_MS, 0.0, 1.0);
        double radius = 0.22 + VOID_MAX_RADIUS * (1.0 - t);
        double vertical = 0.35 + 1.05 * (1.0 - t);

        int count = Math.max(8, (int) Math.round(VOID_PARTICLES_PER_TICK * (0.70 + t * 0.55)));
        for (int i = 0; i < count; i++) {
            double a = Math.random() * Math.PI * 2.0;
            double r = radius * (0.55 + Math.random() * 0.45);
            double px = eb.basePos.x + Math.cos(a) * r;
            double py = eb.basePos.y + (Math.random() - 0.5) * vertical;
            double pz = eb.basePos.z + Math.sin(a) * r;

            Vec3d inward = eb.basePos.subtract(new Vec3d(px, py, pz));
            if (inward.lengthSquared() > 1.0E-6) inward = inward.normalize();
            double speed = 0.10 + t * 0.14 + Math.random() * 0.08;

            ParticleEffect p;
            if (i % 6 == 0) p = ParticleTypes.REVERSE_PORTAL;
            else if (i % 3 == 0) p = VOID_DARK;
            else p = VOID_PURPLE;

            world.addParticle(p, px, py, pz, inward.x * speed, inward.y * speed, inward.z * speed);
        }

        if (eb.tick % 2 == 0) {
            world.addParticle(ParticleTypes.WITCH, eb.basePos.x, eb.basePos.y, eb.basePos.z, 0.0, 0.01, 0.0);
        }
    }

    private static void finishVoid(ClientWorld world, VoidBurst eb) {
        Vec3d c = eb.basePos;
        world.addParticle(ParticleTypes.EXPLOSION, c.x, c.y, c.z, 0.0, 0.0, 0.0);
        for (int i = 0; i < 24; i++) {
            double a = Math.random() * Math.PI * 2.0;
            double vy = (Math.random() - 0.5) * 0.18;
            double speed = 0.08 + Math.random() * 0.12;
            world.addParticle(ParticleTypes.PORTAL, c.x, c.y, c.z, Math.cos(a) * speed, vy, Math.sin(a) * speed);
        }
        SoundEvent s = soundOfByIdFallback("entity.enderman.teleport", SoundEvents.ENTITY_ENDERMAN_TELEPORT);
        if (s != null) world.playSound(c.x, c.y, c.z, s, SoundCategory.PLAYERS, 1.2f, 0.82f, false);
    }

    private static void spawnRose(ClientWorld world, Vec3d pos) {
        double x = pos.x;
        double y = pos.y + 0.95;
        double z = pos.z;

        // Bright inner bloom: a compact flash makes the flower feel like it
        // actually opens before the larger petal cloud expands around it.
        for (int i = 0; i < 22; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double horizontal = 0.08 + Math.random() * 0.22;
            double vx = Math.cos(angle) * horizontal;
            double vz = Math.sin(angle) * horizontal;
            double vy = 0.04 + Math.random() * 0.22;

            ParticleEffect p = (i % 4 == 0) ? ParticleTypes.END_ROD : ROSE_GLOW;
            world.addParticle(
                    p,
                    x + (Math.random() - 0.5) * 0.18,
                    y + (Math.random() - 0.5) * 0.28,
                    z + (Math.random() - 0.5) * 0.18,
                    vx, vy, vz
            );
        }

        // Main rose blossom. Petals spiral outward instead of simply rising,
        // with three colour/particle layers so the cloud has visible depth.
        for (int i = 0; i < ROSE_PARTICLES; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double radius = 0.20 + Math.random() * 1.05;
            double px = x + Math.cos(angle) * radius;
            double py = y + (Math.random() - 0.5) * 1.15;
            double pz = z + Math.sin(angle) * radius;

            double outward = 0.018 + Math.random() * 0.045;
            double swirl = 0.035 + Math.random() * 0.055;
            double vx = Math.cos(angle) * outward - Math.sin(angle) * swirl;
            double vz = Math.sin(angle) * outward + Math.cos(angle) * swirl;
            double vy = 0.035 + Math.random() * 0.105;

            ParticleEffect p;
            if (i % 3 == 0) p = ParticleTypes.CHERRY_LEAVES;
            else if (i % 5 == 0) p = ROSE_GLOW;
            else p = (i % 2 == 0) ? ROSE_PINK : ROSE_RED;

            world.addParticle(p, px, py, pz, vx, vy, vz);
        }

        // Double ground bloom: an inner bright ring and a wider alternating
        // pink/red ring give the effect a clear flower-shaped footprint.
        for (int ring = 0; ring < 2; ring++) {
            double radius = (ring == 0) ? 0.52 : 0.92;
            int count = (ring == 0) ? ROSE_RING_PARTICLES / 2 : ROSE_RING_PARTICLES;
            for (int i = 0; i < count; i++) {
                double a = Math.PI * 2.0 * i / count + ring * 0.08;
                ParticleEffect p = (ring == 0)
                        ? ROSE_GLOW
                        : ((i % 2 == 0) ? ROSE_PINK : ROSE_RED);
                world.addParticle(
                        p,
                        x + Math.cos(a) * radius,
                        pos.y + 0.10 + Math.random() * 0.05,
                        z + Math.sin(a) * radius,
                        Math.cos(a) * 0.012,
                        0.015 + Math.random() * 0.018,
                        Math.sin(a) * 0.012
                );
            }
        }

        // Petal rain above the victim keeps the finish visible for a little
        // longer without turning Rose into another explosion-style effect.
        for (int i = 0; i < ROSE_FALLING_PETALS; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double radius = 0.25 + Math.random() * 1.10;
            double px = x + Math.cos(angle) * radius;
            double py = y + 0.85 + Math.random() * 1.15;
            double pz = z + Math.sin(angle) * radius;
            double drift = 0.015 + Math.random() * 0.035;

            ParticleEffect p = (i % 2 == 0) ? ParticleTypes.CHERRY_LEAVES : ROSE_PINK;
            world.addParticle(
                    p, px, py, pz,
                    -Math.sin(angle) * drift,
                    -0.025 - Math.random() * 0.035,
                    Math.cos(angle) * drift
            );
        }

        // Layer a soft leaf sound with a higher crystalline chime. The first
        // reads as petals bursting outward; the second provides a clean kill cue.
        SoundEvent petals = soundOfByIdFallback(
                "block.cherry_leaves.break",
                SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME
        );
        if (petals != null) {
            world.playSound(x, y, z, petals, SoundCategory.PLAYERS, 1.15f, 1.08f, false);
        }

        SoundEvent chime = soundOfByIdFallback(
                "block.amethyst_cluster.break",
                SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME
        );
        if (chime != null) {
            world.playSound(x, y, z, chime, SoundCategory.PLAYERS, 1.05f, 1.48f, false);
        }
    }

    private static void spawnBlood(ClientWorld world, Vec3d pos) {
        double x = pos.x;
        double y = pos.y + 1.0;
        double z = pos.z;

        // Dense two-tone burst from roughly chest height. Most particles travel
        // sideways, with a smaller upward component so the effect reads as a hit.
        for (int i = 0; i < BLOOD_BURST_PARTICLES; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double speed = 0.16 + Math.random() * 0.34;
            double horizontal = 0.70 + Math.random() * 0.30;
            double vx = Math.cos(angle) * speed * horizontal;
            double vz = Math.sin(angle) * speed * horizontal;
            double vy = -0.08 + Math.random() * 0.38;

            ParticleEffect dust = (i % 4 == 0) ? BLOOD_DARK : BLOOD_BRIGHT;
            world.addParticle(
                    dust,
                    x + (Math.random() - 0.5) * 0.28,
                    y + (Math.random() - 0.5) * 0.45,
                    z + (Math.random() - 0.5) * 0.28,
                    vx, vy, vz
            );

            if (i % 7 == 0) {
                world.addParticle(
                        ParticleTypes.DAMAGE_INDICATOR,
                        x + (Math.random() - 0.5) * 0.35,
                        y + Math.random() * 0.35,
                        z + (Math.random() - 0.5) * 0.35,
                        vx * 0.35,
                        0.04 + Math.random() * 0.10,
                        vz * 0.35
                );
            }
        }

        // A flatter secondary splash gives the burst a grounded finish rather
        // than looking like a floating red sphere.
        double groundY = pos.y + 0.08;
        for (int i = 0; i < BLOOD_GROUND_PARTICLES; i++) {
            double angle = Math.PI * 2.0 * i / BLOOD_GROUND_PARTICLES + (Math.random() - 0.5) * 0.16;
            double radius = 0.25 + Math.random() * 0.80;
            double px = x + Math.cos(angle) * radius;
            double pz = z + Math.sin(angle) * radius;
            ParticleEffect dust = (i % 3 == 0) ? BLOOD_DARK : BLOOD_BRIGHT;
            world.addParticle(
                    dust, px, groundY + Math.random() * 0.08, pz,
                    Math.cos(angle) * 0.035,
                    0.005 + Math.random() * 0.018,
                    Math.sin(angle) * 0.035
            );
        }

        SoundEvent hit = soundOfByIdFallback("entity.player.attack.crit", SoundEvents.ENTITY_GENERIC_HURT);
        if (hit != null) {
            world.playSound(x, y, z, hit, SoundCategory.PLAYERS, 1.1f, 0.78f, false);
        }
    }

    /** Vanilla TNT-style explosion particle, separate from the custom explosion effect. */
    private static void spawnTnt(ClientWorld world, Vec3d pos) {
        double x = pos.x;
        double y = pos.y + 0.95;
        double z = pos.z;

        // This is the vanilla large explosion emitter particle used for TNT-like blasts.
        // Client particles and audio only: no explosion entity, block damage, or knockback.
        world.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 0.0, 0.0, 0.0);

        SoundEvent boom = soundOfByIdFallback("entity.generic.explode", SoundEvents.ENTITY_GENERIC_EXPLODE);
        if (boom != null) {
            world.playSound(x, y, z, boom, SoundCategory.PLAYERS, 1.0f, 1.0f, false);
        }
    }

    private static void spawnExplosion(ClientWorld world, Vec3d pos) {
        double x = pos.x;
        double y = pos.y + 0.85;
        double z = pos.z;

        // Bright compact core. Nothing here creates a real Minecraft explosion;
        // this is purely client-side particles and sound.
        world.addParticle(ParticleTypes.EXPLOSION, x, y, z, 0.0, 0.0, 0.0);
        world.addParticle(ParticleTypes.EXPLOSION, x + 0.22, y + 0.12, z - 0.12, 0.0, 0.0, 0.0);
        world.addParticle(ParticleTypes.EXPLOSION, x - 0.18, y - 0.05, z + 0.18, 0.0, 0.0, 0.0);

        // 360-degree shockwave. Alternating POOF/FLAME particles make a bright
        // leading edge while their outward velocity keeps the ring expanding.
        for (int i = 0; i < EXPLOSION_RING_PARTICLES; i++) {
            double angle = Math.PI * 2.0 * i / EXPLOSION_RING_PARTICLES;
            double jitter = (Math.random() - 0.5) * 0.12;
            double radius = 0.28 + Math.random() * 0.18;
            double dx = Math.cos(angle + jitter);
            double dz = Math.sin(angle + jitter);
            double speed = 0.28 + Math.random() * 0.22;

            ParticleEffect ring = (i % 4 == 0) ? ParticleTypes.FLAME : ParticleTypes.POOF;
            world.addParticle(
                    ring,
                    x + dx * radius,
                    y - 0.10 + Math.random() * 0.28,
                    z + dz * radius,
                    dx * speed,
                    0.015 + Math.random() * 0.09,
                    dz * speed
            );

            if (i % 6 == 0) {
                double outer = EXPLOSION_RING_RADIUS * (0.82 + Math.random() * 0.18);
                world.addParticle(
                        ParticleTypes.CRIT,
                        x + dx * outer,
                        y + (Math.random() - 0.5) * 0.35,
                        z + dz * outer,
                        dx * 0.08,
                        Math.random() * 0.08,
                        dz * 0.08
                );
            }
        }

        // Smoke lingers behind the fast shockwave and gives the explosion depth.
        for (int i = 0; i < EXPLOSION_SMOKE_PARTICLES; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double radius = Math.random() * 0.70;
            double px = x + Math.cos(angle) * radius;
            double pz = z + Math.sin(angle) * radius;
            ParticleEffect smoke = (i % 3 == 0) ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE;
            world.addParticle(
                    smoke,
                    px,
                    y - 0.20 + Math.random() * 0.85,
                    pz,
                    (Math.random() - 0.5) * 0.09,
                    0.06 + Math.random() * 0.14,
                    (Math.random() - 0.5) * 0.09
            );
        }

        SoundEvent boom = soundOfByIdFallback("entity.generic.explode", SoundEvents.ENTITY_GENERIC_EXPLODE);
        if (boom != null) {
            world.playSound(x, y, z, boom, SoundCategory.PLAYERS, 1.65f, 0.92f, false);
        }
    }

    private static void spawnThunder(ClientWorld world, Vec3d pos) {
        double x = pos.x;
        double y = pos.y;
        double z = pos.z;

        LightningEntity bolt = null;
        try {
            bolt = EntityType.LIGHTNING_BOLT.create(world, SpawnReason.TRIGGERED);
        } catch (Throwable ignored) {}

        if (bolt == null) {
            try {
                bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
            } catch (Throwable ignored) {}
        }

        if (bolt == null) return;

        bolt.refreshPositionAfterTeleport(x, y, z);
        try {
            bolt.setCosmetic(true);
        } catch (Throwable ignored) {}

        world.addEntity(bolt);

        SoundEvent s1 = soundOf(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER);
        if (s1 != null) {
            world.playSound(x, y, z, s1, SoundCategory.WEATHER, 2.0f, 1.0f, false);
        }

        SoundEvent s2 = soundOf(SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT);
        if (s2 != null) {
            world.playSound(x, y, z, s2, SoundCategory.WEATHER, 2.0f, 1.0f, false);
        }
    }

    private static void spawnTotem(ClientWorld world, Vec3d pos) {
        double x = pos.x;
        double y = pos.y + 1.0;
        double z = pos.z;

        for (int i = 0; i < TOTEM_PARTICLE_COUNT; i++) {
            double vx = (Math.random() - 0.5) * 0.75;
            double vy = Math.random() * 0.85;
            double vz = (Math.random() - 0.5) * 0.75;
            world.addParticle(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, vx, vy, vz);
        }

        SoundEvent s = soundOf(SoundEvents.ITEM_TOTEM_USE);
        if (s != null) {
            world.playSound(x, y, z, s, SoundCategory.PLAYERS, 1.0f, 1.0f, false);
        }
    }

    private static void spawnWing(ClientWorld world, Vec3d pos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d base = new Vec3d(pos.x, pos.y + 1.35, pos.z);

        Vec3d camPos;
        try {
            camPos = (mc != null && mc.gameRenderer != null && mc.gameRenderer.getCamera() != null)
                    ? mc.gameRenderer.getCamera().getPos()
                    : base.add(0, 0, 1);
        } catch (Throwable t) {
            camPos = base.add(0, 0, 1);
        }

        Vec3d normal = camPos.subtract(base);
        if (normal.lengthSquared() < 1.0E-6) normal = new Vec3d(0, 0, 1);
        normal = normal.normalize();

        Vec3d worldUp = new Vec3d(0, 1, 0);
        Vec3d right = worldUp.crossProduct(normal);
        if (right.lengthSquared() < 1.0E-6) right = new Vec3d(1, 0, 0);
        right = right.normalize();

        Vec3d upPlane = normal.crossProduct(right);
        if (upPlane.lengthSquared() < 1.0E-6) upPlane = new Vec3d(0, 1, 0);
        upPlane = upPlane.normalize();

        final double spanMax = 1.35;
        final double riseMax = 1.25;
        final double backMax = 0.22;

        for (int i = 0; i < 10; i++) {
            double jx = (Math.random() - 0.5) * 0.10;
            double jy = Math.random() * 0.18;
            double jz = (Math.random() - 0.5) * 0.10;
            world.addParticle(
                    ParticleTypes.END_ROD,
                    base.x + jx,
                    base.y + jy,
                    base.z + jz,
                    (Math.random() - 0.5) * 0.02,
                    0.03 + Math.random() * 0.03,
                    (Math.random() - 0.5) * 0.02
            );
        }

        for (int i = 0; i < 10; i++) {
            double jx = (Math.random() - 0.5) * 0.12;
            double jy = Math.random() * 0.20;
            double jz = (Math.random() - 0.5) * 0.12;
            world.addParticle(
                    ParticleTypes.CLOUD,
                    base.x + jx,
                    base.y + jy,
                    base.z + jz,
                    (Math.random() - 0.5) * 0.015,
                    0.02 + Math.random() * 0.02,
                    (Math.random() - 0.5) * 0.015
            );
        }

        for (int side = 0; side < 2; side++) {
            double sign = (side == 0) ? -1.0 : 1.0;

            for (int i = 0; i < WING_PARTICLE_COUNT; i++) {
                double t = Math.random();
                double span = spanMax * (0.25 + 0.75 * t);
                double rise = riseMax * Math.sin(t * Math.PI);
                double back = backMax * (1.0 - t);
                double feather = (Math.random() - 0.5) * 0.08;
                double flutter = (Math.random() - 0.5) * 0.06;

                Vec3d p = base
                        .add(right.multiply(sign * span))
                        .add(upPlane.multiply(rise))
                        .subtract(normal.multiply(back))
                        .add(right.multiply(sign * feather))
                        .add(upPlane.multiply(flutter));

                double vx = sign * (0.03 + Math.random() * 0.03)
                        + (Math.random() - 0.5) * 0.015;
                double vy = 0.05 + Math.random() * 0.07;
                double vz = (Math.random() - 0.5) * 0.015;

                Vec3d vel = right.multiply(vx)
                        .add(upPlane.multiply(vy))
                        .add(normal.multiply(vz));

                world.addParticle(
                        ParticleTypes.CLOUD,
                        p.x,
                        p.y,
                        p.z,
                        vel.x,
                        vel.y,
                        vel.z
                );

                if (i % 12 == 0) {
                    world.addParticle(
                            ParticleTypes.END_ROD,
                            p.x,
                            p.y,
                            p.z,
                            vel.x * 0.35,
                            vel.y * 0.35,
                            vel.z * 0.35
                    );
                }
            }
        }

        SoundEvent boom = soundOfByIdFallback("block.vault.break", SoundEvents.BLOCK_ANVIL_LAND);
        SoundEvent ding = soundOfByIdFallback("block.amethyst_cluster.break", SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME);
        if (boom != null) playMaster(boom, 2.0f, 1.00f);
        if (ding != null) playMaster(ding, 3.0f, 1.35f);
    }

    private static void spawnPotionDrop(ClientWorld world, Vec3d targetPos, long nowMs) {
        double x = targetPos.x;
        double z = targetPos.z;
        double startY = computePotionSpawnY(world, targetPos);

        ItemEntity item;
        try {
            item = new ItemEntity(world, x, startY, z, new ItemStack(Items.SPLASH_POTION));
        } catch (Throwable t) {
            return;
        }

        try {
            item.setNoGravity(true);
        } catch (Throwable ignored) {}

        item.setVelocity(0.0, 0.0, 0.0);
        world.addEntity(item);

        double driftX = (Math.random() - 0.5) * 0.01;
        double driftZ = (Math.random() - 0.5) * 0.01;

        potionDrops.put(item.getId(), new PotionDrop(
                item.getId(),
                targetPos,
                nowMs,
                0.62,
                driftX,
                driftZ
        ));
    }

    private static void tickPotionDrops(ClientWorld world, long nowMs) {
        if (potionDrops.isEmpty()) return;

        Iterator<Map.Entry<Integer, PotionDrop>> it = potionDrops.entrySet().iterator();
        while (it.hasNext()) {
            PotionDrop pd = it.next().getValue();
            Entity e = world.getEntityById(pd.entityId);
            if (e == null) {
                it.remove();
                continue;
            }

            double nx = e.getX() + pd.driftX;
            double ny = e.getY() - pd.fallSpeed;
            double nz = e.getZ() + pd.driftZ;

            try {
                e.refreshPositionAfterTeleport(nx, ny, nz);
            } catch (Throwable ignored) {}

            try {
                e.setVelocity(0.0, 0.0, 0.0);
            } catch (Throwable ignored) {}

            for (int i = 0; i < POTION_TRAIL_PARTICLES; i++) {
                double px = nx + (Math.random() - 0.5) * 0.15;
                double py = ny + 0.15 + Math.random() * 0.15;
                double pz = nz + (Math.random() - 0.5) * 0.15;
                world.addParticle(ParticleTypes.EFFECT, px, py, pz, 0.0, 0.02, 0.0);
            }

            boolean timeout = (nowMs - pd.spawnMs) > POTION_TIMEOUT_MS;
            boolean reached = ny <= (pd.targetPos.y + 0.15);

            if (reached || timeout) {
                potionSplash(world, pd.targetPos);
                try {
                    e.discard();
                } catch (Throwable ignored) {}
                it.remove();
            }
        }
    }

    private static void potionSplash(ClientWorld world, Vec3d pos) {
        double x = pos.x;
        double y = pos.y + 0.2;
        double z = pos.z;

        for (int i = 0; i < POTION_SPLASH_PARTICLES; i++) {
            double vx = (Math.random() - 0.5) * 0.65;
            double vy = Math.random() * 0.55;
            double vz = (Math.random() - 0.5) * 0.65;
            world.addParticle(
                    (i % 3 == 0) ? ParticleTypes.INSTANT_EFFECT : ParticleTypes.EFFECT,
                    x,
                    y,
                    z,
                    vx,
                    vy,
                    vz
            );
        }

        spawnAscension(world, x, pos.y + 0.10, z);

        SoundEvent beacon = soundOfByIdFallback("block.beacon.activate", SoundEvents.BLOCK_BEACON_ACTIVATE);
        if (beacon != null) {
            world.playSound(x, y, z, beacon, SoundCategory.PLAYERS, 1.15f, 1.00f, false);
        }

        SoundEvent brk = soundOfByIdFallback("entity.splash_potion.break", SoundEvents.ENTITY_GENERIC_SPLASH);
        if (brk != null) {
            world.playSound(x, y, z, brk, SoundCategory.PLAYERS, 1.0f, 1.0f, false);
        }
    }

    private static void spawnAscension(ClientWorld world, double x, double y, double z) {
        final int pCount = 42;

        for (int i = 0; i < pCount; i++) {
            double rr = 0.35 + Math.random() * 0.55;
            double a = Math.random() * Math.PI * 2.0;
            double px = x + Math.cos(a) * rr;
            double pz = z + Math.sin(a) * rr;
            double py = y + Math.random() * 0.25;
            double vy = 0.18 + Math.random() * 0.22;

            world.addParticle(ParticleTypes.END_ROD, px, py, pz, 0.0, vy, 0.0);

            if (i % 4 == 0) {
                world.addParticle(ParticleTypes.ENCHANT, px, py, pz, 0.0, 0.06, 0.0);
            }
        }

        final int beamSteps = 18;
        final double height = 2.15;

        for (int i = 0; i < beamSteps; i++) {
            double t = i / (double) (beamSteps - 1);
            double py = y + t * height;
            double jx = (Math.random() - 0.5) * 0.05;
            double jz = (Math.random() - 0.5) * 0.05;

            world.addParticle(
                    ParticleTypes.END_ROD,
                    x + jx,
                    py,
                    z + jz,
                    0.0,
                    0.03 + Math.random() * 0.05,
                    0.0
            );

            if (i % 5 == 0) {
                world.addParticle(ParticleTypes.ENCHANT, x + jx, py, z + jz, 0.0, 0.02, 0.0);
            }
        }
    }

    // ===== Soul / glitch =====
    private static void spawnSoul(ClientWorld world, Vec3d pos, long nowMs) {
        Vec3d base = new Vec3d(pos.x, pos.y + 0.95, pos.z);

        long seed = mix64(
                Double.doubleToLongBits(base.x)
                        ^ (Double.doubleToLongBits(base.y) * 31L)
                        ^ (Double.doubleToLongBits(base.z) * 131L)
                        ^ nowMs
        );

        int id = glitchSeq++;
        GlitchBurst gb = new GlitchBurst(id, base, nowMs, nowMs + GLITCH_DURATION_MS, seed);
        glitchBursts.put(id, gb);

        SoundEvent sfx = soundOfByIdFallback(
                "minecraft:entity.skeleton.converted_to_stray",
                SoundEvents.ENTITY_SKELETON_AMBIENT
        );
        if (sfx != null) playMaster(sfx, 1.25f, 1.00f);

        emitGlitchFrame(world, gb, nowMs);
    }

    private static void tickGlitchBursts(ClientWorld world, long nowMs) {
        if (glitchBursts.isEmpty()) return;

        Iterator<Map.Entry<Integer, GlitchBurst>> it = glitchBursts.entrySet().iterator();
        while (it.hasNext()) {
            GlitchBurst gb = it.next().getValue();

            if (nowMs >= gb.endMs) {
                it.remove();
                continue;
            }

            emitGlitchFrame(world, gb, nowMs);
        }
    }

    private static void emitGlitchFrame(ClientWorld world, GlitchBurst gb, long nowMs) {
        gb.tick++;

        double age = Math.max(0.0, nowMs - gb.startMs);
        double t = MathHelper.clamp(age / (double) GLITCH_DURATION_MS, 0.0, 1.0);
        double intensity = 1.0 - (t * t);
        double radius = GLITCH_RADIUS * (0.75 + 0.25 * intensity);

        double shakeX = nextSigned(gb) * 0.07 * intensity;
        double shakeY = nextSigned(gb) * 0.04 * intensity;
        double shakeZ = nextSigned(gb) * 0.07 * intensity;
        Vec3d base = gb.basePos.add(shakeX, shakeY, shakeZ);

        double pulse = (gb.tick % 2 == 0)
                ? 1.0
                : (0.55 + next01(gb) * 0.35);

        int count = (int) Math.round(
                (GLITCH_PARTICLES_PER_TICK + next01(gb) * 14.0)
                        * intensity
                        * pulse
        );
        if (count < 6) count = 6;

        for (int i = 0; i < count; i++) {
            double pick = next01(gb);
            double spike = (pick < 0.12) ? 1.7 : 1.0;

            double ox = nextSigned(gb) * radius * spike;
            double oy = nextSigned(gb) * (0.55 + next01(gb) * 0.55);
            double oz = nextSigned(gb) * radius * spike;

            ParticleEffect p;
            if (pick < 0.33) p = ParticleTypes.SOUL;
            else if (pick < 0.72) p = ParticleTypes.ELECTRIC_SPARK;
            else p = ParticleTypes.CRIT;

            double velScale = (p == ParticleTypes.ELECTRIC_SPARK)
                    ? (0.22 + next01(gb) * 0.32)
                    : (0.10 + next01(gb) * 0.22);
            velScale *= intensity;

            double vx = ox * velScale + nextSigned(gb) * 0.03;
            double vy = (0.02 + next01(gb) * 0.18) * intensity
                    + nextSigned(gb) * 0.04;
            double vz = oz * velScale + nextSigned(gb) * 0.03;

            if (p == ParticleTypes.SOUL) {
                vy += 0.03 * intensity;
            }

            world.addParticle(
                    p,
                    base.x + ox,
                    base.y + oy,
                    base.z + oz,
                    vx,
                    vy,
                    vz
            );

            if (i % 11 == 0) {
                emitGlitchShard(world, gb, base, intensity);
            }
        }
    }

    private static void emitGlitchShard(ClientWorld world, GlitchBurst gb, Vec3d base, double intensity) {
        double a = next01(gb) * Math.PI * 2.0;
        double b = nextSigned(gb) * 0.35;

        Vec3d dir = new Vec3d(Math.cos(a), b, Math.sin(a));
        if (dir.lengthSquared() < 1.0E-6) dir = new Vec3d(1, 0, 0);
        dir = dir.normalize();

        int steps = 5 + (int) Math.floor(next01(gb) * 3.0);
        double len = (0.18 + next01(gb) * 0.28)
                * (0.65 + 0.35 * intensity);

        for (int i = 0; i < steps; i++) {
            double tt = i / (double) (steps - 1);
            Vec3d p = base.add(dir.multiply((tt - 0.5) * len));

            world.addParticle(
                    ParticleTypes.ELECTRIC_SPARK,
                    p.x,
                    p.y,
                    p.z,
                    0.0,
                    (0.02 + next01(gb) * 0.04) * intensity,
                    0.0
            );

            if (i == 0 || i == steps - 1) {
                world.addParticle(
                        ParticleTypes.CRIT,
                        p.x,
                        p.y,
                        p.z,
                        nextSigned(gb) * 0.03,
                        next01(gb) * 0.08 * intensity,
                        nextSigned(gb) * 0.03
                );
            }
        }
    }

    // ===== PRNG helpers =====
    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    private static long nextState(GlitchBurst gb) {
        long x = gb.state;
        x ^= (x << 13);
        x ^= (x >>> 7);
        x ^= (x << 17);
        gb.state = x;
        return x;
    }

    private static double next01(GlitchBurst gb) {
        long s = nextState(gb);
        return ((s >>> 11) * 0x1.0p-53);
    }

    private static double nextSigned(GlitchBurst gb) {
        return next01(gb) * 2.0 - 1.0;
    }

    private static double computePotionSpawnY(ClientWorld world, Vec3d targetPos) {
        double desired = targetPos.y + POTION_DROP_HEIGHT;
        int bx = MathHelper.floor(targetPos.x);
        int bz = MathHelper.floor(targetPos.z);
        int fromY = MathHelper.floor(targetPos.y) + 1;
        int toY = MathHelper.floor(desired);

        for (int y = fromY; y <= toY; y++) {
            BlockPos bp = new BlockPos(bx, y, bz);
            if (!world.getBlockState(bp).getCollisionShape(world, bp).isEmpty()) {
                return y - 0.35;
            }
        }

        return desired;
    }

    private static SoundEvent soundOfByIdFallback(String id, Object fallback) {
        try {
            final Identifier ident = (id != null && id.indexOf(':') >= 0)
                    ? Identifier.of(id)
                    : Identifier.of("minecraft", id);

            if (Registries.SOUND_EVENT.containsId(ident)) {
                SoundEvent se = Registries.SOUND_EVENT.get(ident);
                if (se != null) return se;
            }
        } catch (Throwable ignored) {}

        return soundOf(fallback);
    }

    // ===== Config IO =====
    private static void loadConfig() {
        try {
            if (!Files.exists(CONFIG_PATH)) return;

            try (BufferedReader br = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
                JsonObject obj = GSON.fromJson(br, JsonObject.class);
                if (obj == null) return;

                if (obj.has("enabled")) {
                    enabled = obj.get("enabled").getAsBoolean();
                }

                if (obj.has("effect")) {
                    EffectType parsed = EffectType.parse(obj.get("effect").getAsString());
                    if (parsed != null) effect = parsed;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void saveConfig() {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            obj.addProperty("effect", (effect != null) ? effect.id() : "thunder");

            try (BufferedWriter bw = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
                GSON.toJson(obj, bw);
            }
        } catch (Throwable ignored) {}
    }

    // ===== Chat utils =====
    private static void send(MutableText text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(text);
    }

    private static MutableText statusLine() {
        return header()
                .append(enabled
                        ? colored("ON", 0x9CFF6F, true)
                        : colored("OFF", 0xFF5577, true))
                .append(dark(" | "))
                .append(colored(
                        (effect != null) ? effect.displayName() : "—",
                        EFFECT_NAME_COLOR,
                        true
                ));
    }

    private static MutableText header() {
        MutableText out = Text.empty();
        out.append(Text.literal("[")
                .styled(s -> s.withColor(TextColor.fromRgb(0x666666))));
        out.append(gradient("152", 0xFFE4FF, 0xC0FFFF, true));
        out.append(Text.literal("] ")
                .styled(s -> s.withColor(TextColor.fromRgb(0x666666))));
        return out;
    }

    private static MutableText gradient(String s, int fromRgb, int toRgb, boolean bold) {
        MutableText out = Text.empty();
        if (s == null || s.isEmpty()) return out;

        int len = s.length();
        if (len == 1) {
            return Text.literal(s).styled(st ->
                    st.withColor(TextColor.fromRgb(fromRgb)).withBold(bold)
            );
        }

        for (int i = 0; i < len; i++) {
            float t = i / (float) (len - 1);
            int r = (int) (((fromRgb >> 16) & 0xFF) * (1 - t)
                    + ((toRgb >> 16) & 0xFF) * t);
            int g = (int) (((fromRgb >> 8) & 0xFF) * (1 - t)
                    + ((toRgb >> 8) & 0xFF) * t);
            int b = (int) ((fromRgb & 0xFF) * (1 - t)
                    + (toRgb & 0xFF) * t);
            int rgb = (r << 16) | (g << 8) | b;

            out.append(Text.literal(String.valueOf(s.charAt(i)))
                    .styled(st -> st.withColor(TextColor.fromRgb(rgb)).withBold(bold)));
        }

        return out;
    }

    private static MutableText gray(String s) {
        return Text.literal(s)
                .styled(st -> st.withColor(TextColor.fromRgb(0xAAAAAA)));
    }

    private static MutableText dark(String s) {
        return Text.literal(s)
                .styled(st -> st.withColor(TextColor.fromRgb(0x666666)));
    }

    private static MutableText light(String s) {
        return Text.literal(s)
                .styled(st -> st.withColor(TextColor.fromRgb(0xDDDDDD)));
    }

    private static MutableText colored(String s, int rgb, boolean bold) {
        return Text.literal(s).styled(st ->
                st.withColor(TextColor.fromRgb(rgb & 0xFFFFFF)).withBold(bold)
        );
    }

    private static SoundEvent soundOf(Object maybeEntryOrEvent) {
        if (maybeEntryOrEvent == null) return null;
        if (maybeEntryOrEvent instanceof SoundEvent se) return se;

        if (maybeEntryOrEvent instanceof RegistryEntry<?> re) {
            try {
                Object v = re.value();
                if (v instanceof SoundEvent se) return se;
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private static void playMaster(SoundEvent sound, float volume, float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        try {
            mc.getSoundManager().play(PositionedSoundInstance.master(sound, pitch, volume));
        } catch (Throwable ignored) {
            try {
                if (mc.world != null) {
                    Vec3d p = (mc.player != null)
                            ? mc.player.getPos()
                            : new Vec3d(0, 0, 0);
                    mc.world.playSound(
                            p.x,
                            p.y,
                            p.z,
                            sound,
                            SoundCategory.MASTER,
                            volume,
                            pitch,
                            false
                    );
                }
            } catch (Throwable ignored2) {}
        }
    }
}