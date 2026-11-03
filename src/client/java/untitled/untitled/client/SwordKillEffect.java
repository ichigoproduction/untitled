package untitled.untitled.client;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Client-only, particle-drawn sword that drops into the ground after a kill.
 * Does not spawn a physical sword, change blocks, or send server packets.
 */
public final class SwordKillEffect {
    private static final long DROP_MS = 280;
    private static final long HOLD_MS = 1050;
    private static final long TOTAL_MS = DROP_MS + HOLD_MS;
    private static final int MAX_ACTIVE = 6;
    private static final DustParticleEffect STEEL = new DustParticleEffect(0xCDD9E7, 1.05f);
    private static final DustParticleEffect EDGE = new DustParticleEffect(0xF8FAFF, 0.90f);
    private static final DustParticleEffect SHADOW = new DustParticleEffect(0x53657A, 0.95f);
    private static final DustParticleEffect GUARD = new DustParticleEffect(0xD7B876, 1.00f);
    private static final DustParticleEffect GRIP = new DustParticleEffect(0x392C33, 0.90f);
    private static final List<SwordDrop> ACTIVE = new ArrayList<>();

    private SwordKillEffect() {}

    private static final class SwordDrop {
        final ClientWorld world;
        final Vec3d ground;
        final long started;
        final double angle;
        boolean impacted;

        SwordDrop(ClientWorld world, Vec3d ground, long started) {
            this.world = world;
            this.ground = ground;
            this.started = started;
            this.angle = Math.random() * Math.PI * 2.0;
        }
    }

    public static void spawn(ClientWorld world, Vec3d position, long nowMs) {
        if (world == null || position == null) return;
        if (ACTIVE.size() >= MAX_ACTIVE) ACTIVE.remove(0);
        SwordDrop drop = new SwordDrop(world, position, nowMs);
        ACTIVE.add(drop);
        renderSword(world, drop, 3.1);
    }

    public static void tick(ClientWorld world, long nowMs) {
        Iterator<SwordDrop> it = ACTIVE.iterator();
        while (it.hasNext()) {
            SwordDrop drop = it.next();
            if (drop.world != world || nowMs < drop.started || nowMs - drop.started > TOTAL_MS) {
                it.remove();
                continue;
            }
            long age = nowMs - drop.started;
            if (age < DROP_MS) {
                double t = age / (double) DROP_MS;
                // Fast accelerating vertical drop; the blade point touches the ground at t=1.
                double offset = 3.1 * (1.0 - t * t);
                renderSword(world, drop, offset);
                if (age % 3 == 0) {
                    Vec3d tip = drop.ground.add(0, offset + 0.07, 0);
                    world.addParticle(ParticleTypes.END_ROD, tip.x, tip.y, tip.z, 0, 0.03, 0);
                }
            } else {
                if (!drop.impacted) {
                    drop.impacted = true;
                    impact(world, drop.ground);
                }
                // The sword remains visibly embedded for a short time, then fades away.
                if (age < DROP_MS + 800 && age % 2 == 0) {
                    renderSword(world, drop, 0.0);
                }
            }
        }
    }

    /** Axis-aligned height with a small leaning angle for the blade-in-ground pose. */
    private static Vec3d point(SwordDrop drop, double h, double lateral, double offset) {
        double lean = h * 0.13;
        double side = lateral + lean;
        return new Vec3d(
            drop.ground.x + Math.cos(drop.angle) * side,
            drop.ground.y + offset + h,
            drop.ground.z + Math.sin(drop.angle) * side
        );
    }

    private static void line(ClientWorld world, SwordDrop drop, double x0, double y0, double x1, double y1,
                             double offset, double step, DustParticleEffect dust) {
        double distance = Math.hypot(x1-x0, y1-y0);
        int count = Math.max(2, (int) Math.ceil(distance / step));
        for (int i = 0; i <= count; i++) {
            double t = i / (double) count;
            Vec3d p = point(drop, y0 + (y1-y0)*t, x0 + (x1-x0)*t, offset);
            world.addParticle(dust, p.x, p.y, p.z, 0, 0, 0);
        }
    }

    private static void renderSword(ClientWorld world, SwordDrop drop, double offset) {
        // Blade: tapered tip, silver core and two bright/dark edges.
        line(world, drop, 0.0, 0.07, 0.0, 0.45, offset, 0.085, EDGE);
        line(world, drop, -0.035, 0.42, -0.085, 1.50, offset, 0.10, EDGE);
        line(world, drop, 0.035, 0.42, 0.085, 1.50, offset, 0.10, SHADOW);
        line(world, drop, 0.0, 0.40, 0.0, 1.52, offset, 0.085, STEEL);
        // Crossguard, grip and pommel.
        line(world, drop, -0.35, 1.55, 0.35, 1.55, offset, 0.075, GUARD);
        line(world, drop, 0.0, 1.61, 0.0, 2.14, offset, 0.075, GRIP);
        line(world, drop, -0.13, 2.22, 0.13, 2.22, offset, 0.065, GUARD);
        line(world, drop, 0.0, 2.13, 0.0, 2.30, offset, 0.065, GUARD);
    }

    private static void impact(ClientWorld world, Vec3d pos) {
        // Deep anvil clang, followed by a quiet metallic shimmer and ground sparks.
        world.playSound(pos.x, pos.y, pos.z, SoundEvents.BLOCK_ANVIL_LAND,
            SoundCategory.PLAYERS, 1.3f, 1.07f, false);
        world.playSound(pos.x, pos.y, pos.z, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
            SoundCategory.PLAYERS, 0.65f, 0.78f, false);
        world.playSound(pos.x, pos.y, pos.z, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT,
            SoundCategory.PLAYERS, 0.65f, 0.85f, false);

        for (int i = 0; i < 36; i++) {
            double a = Math.PI * 2.0 * i / 36.0;
            double radius = 0.15 + Math.random() * 0.7;
            double speed = 0.04 + Math.random() * 0.07;
            world.addParticle(i % 4 == 0 ? ParticleTypes.CRIT : ParticleTypes.CLOUD,
                pos.x + Math.cos(a) * radius, pos.y + 0.08,
                pos.z + Math.sin(a) * radius,
                Math.cos(a) * speed, 0.03 + Math.random() * 0.07, Math.sin(a) * speed);
        }
        for (int i = 0; i < 14; i++) {
            double a = Math.PI * 2.0 * i / 14.0;
            world.addParticle(ParticleTypes.CRIT, pos.x, pos.y + 0.20, pos.z,
                Math.cos(a)*0.20, 0.10 + Math.random()*0.13, Math.sin(a)*0.20);
        }
    }
}
