package untitled.untitled.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Client-only water-themed kill effect.
 * Droplets burst outward, fall under gravity, and leave brief blue splash rings.
 * Uses particle visuals only; no water or glass blocks, physical entities, or server changes.
 */
public final class WaterKillEffect {
    private static final int DROPS_PER_KILL = 28;
    private static final int MAX_DROPS = 112;
    private static final int MAX_PUDDLES = 72;
    private static final long DROP_TIMEOUT_MS = 2200L;
    private static final long PUDDLE_DURATION_MS = 1900L;
    private static final double GRAVITY = 0.035;
    private static final Random RANDOM = new Random();
    private static final DustParticleEffect WATER_BLUE = new DustParticleEffect(0x4ABFEF, 1.10f);
    private static final DustParticleEffect WATER_LIGHT = new DustParticleEffect(0xB0EEFF, 0.94f);
    private static final List<Drop> DROPS = new ArrayList<>();
    private static final List<Puddle> PUDDLES = new ArrayList<>();

    private WaterKillEffect() {}

    private static final class Drop {
        final ClientWorld world;
        final long createdAt;
        Vec3d position;
        Vec3d velocity;

        Drop(ClientWorld world, Vec3d position, Vec3d velocity, long createdAt) {
            this.world = world;
            this.position = position;
            this.velocity = velocity;
            this.createdAt = createdAt;
        }
    }

    private static final class Puddle {
        final ClientWorld world;
        final Vec3d position;
        final double radius;
        final long expiresAt;
        int frames;

        Puddle(ClientWorld world, Vec3d position, double radius, long expiresAt) {
            this.world = world;
            this.position = position;
            this.radius = radius;
            this.expiresAt = expiresAt;
        }
    }

    public static void spawn(ClientWorld world, Vec3d victimPosition, long nowMs) {
        if (world == null || victimPosition == null) return;

        Vec3d center = victimPosition.add(0, 1.05, 0);
        world.playSound(center.x, center.y, center.z,
                resolveSound(SoundEvents.ENTITY_GENERIC_SPLASH),
                SoundCategory.PLAYERS, 0.90f, 1.20f, false);

        for (int i = 0; i < DROPS_PER_KILL; i++) {
            if (DROPS.size() >= MAX_DROPS) DROPS.remove(0);

            double angle = RANDOM.nextDouble() * 2.0 * Math.PI;
            double speed = 0.10 + RANDOM.nextDouble() * 0.20;
            double lift = 0.10 + RANDOM.nextDouble() * 0.18;
            Vec3d origin = center.add(
                    (RANDOM.nextDouble() - 0.5) * 0.35,
                    (RANDOM.nextDouble() - 0.5) * 0.35,
                    (RANDOM.nextDouble() - 0.5) * 0.35);
            DROPS.add(new Drop(world, origin,
                    new Vec3d(Math.cos(angle) * speed, lift, Math.sin(angle) * speed), nowMs));
        }
        // Immediate visible burst, even before projectiles reach the ground.
        for (int i = 0; i < 24; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double velocity = 0.08 + RANDOM.nextDouble() * 0.16;
            world.addParticle(ParticleTypes.SPLASH, center.x, center.y, center.z,
                    Math.cos(angle) * velocity, 0.08 + RANDOM.nextDouble() * 0.1,
                    Math.sin(angle) * velocity);
        }
    }

    public static void tick(ClientWorld world, long nowMs) {
        Iterator<Drop> drops = DROPS.iterator();
        while (drops.hasNext()) {
            Drop drop = drops.next();
            if (drop.world != world || nowMs - drop.createdAt > DROP_TIMEOUT_MS) {
                drops.remove();
                continue;
            }
            Vec3d next = drop.position.add(drop.velocity);
            BlockHitResult hit = world.raycast(new RaycastContext(
                    drop.position, next, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, MinecraftClient.getInstance().player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                Vec3d contact = hit.getPos();
                if (hit.getSide() == Direction.UP) {
                    addPuddle(world, contact, nowMs);
                }
                for (int i = 0; i < 5; i++) {
                    world.addParticle(ParticleTypes.SPLASH,
                            contact.x, contact.y + 0.04, contact.z,
                            (RANDOM.nextDouble() - 0.5) * 0.18,
                            0.06 + RANDOM.nextDouble() * 0.12,
                            (RANDOM.nextDouble() - 0.5) * 0.18);
                }
                drops.remove();
                continue;
            }

            drop.position = next;
            drop.velocity = drop.velocity.multiply(0.985).add(0.0, -GRAVITY, 0.0);
            world.addParticle(ParticleTypes.SPLASH,
                    next.x, next.y, next.z, 0.0, 0.0, 0.0);
            if (RANDOM.nextBoolean()) {
                world.addParticle(RANDOM.nextBoolean() ? WATER_BLUE : WATER_LIGHT,
                        next.x, next.y, next.z, 0.0, 0.0, 0.0);
            }
        }

        Iterator<Puddle> puddles = PUDDLES.iterator();
        while (puddles.hasNext()) {
            Puddle puddle = puddles.next();
            if (puddle.world != world || nowMs >= puddle.expiresAt) {
                puddles.remove();
                continue;
            }
            // Flat, blue-tinted concentric ripples; no actual water block is rendered.
            if (puddle.frames++ % 4 == 0) drawRipple(world, puddle, nowMs);
        }
    }

    private static void addPuddle(ClientWorld world, Vec3d pos, long nowMs) {
        if (PUDDLES.size() >= MAX_PUDDLES) PUDDLES.remove(0);
        PUDDLES.add(new Puddle(world, pos.add(0, 0.035, 0),
                0.20 + RANDOM.nextDouble() * 0.30, nowMs + PUDDLE_DURATION_MS));
        if (RANDOM.nextInt(7) == 0) {
            world.playSound(pos.x, pos.y, pos.z,
                    resolveSound(SoundEvents.BLOCK_BUBBLE_COLUMN_BUBBLE_POP),
                    SoundCategory.PLAYERS, 0.26f, 1.23f, false);
        }
    }

    private static void drawRipple(ClientWorld world, Puddle puddle, long nowMs) {
        double progress = 1.0 - Math.max(0.0, puddle.expiresAt - nowMs)
                / (double) PUDDLE_DURATION_MS;
        double radius = puddle.radius * (0.65 + 0.35 * progress);
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6.0;
            world.addParticle(i % 3 == 0 ? WATER_LIGHT : WATER_BLUE,
                    puddle.position.x + Math.cos(angle) * radius,
                    puddle.position.y,
                    puddle.position.z + Math.sin(angle) * radius,
                    0.0, 0.0, 0.0);
        }
        if (RANDOM.nextInt(4) == 0) {
            world.addParticle(ParticleTypes.SPLASH,
                    puddle.position.x, puddle.position.y + 0.01, puddle.position.z,
                    0.0, 0.015, 0.0);
        }
    }

    private static SoundEvent resolveSound(Object sound) {
        if (sound instanceof SoundEvent event) return event;
        if (sound instanceof RegistryEntry<?> entry && entry.value() instanceof SoundEvent event) return event;
        throw new IllegalArgumentException("Unknown sound event type: " + sound);
    }
}
