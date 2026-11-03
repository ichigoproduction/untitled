package untitled.untitled.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Three-second, randomly colored vanilla beacon beam at the killed player's location.
 * Uses the normal beacon texture via Fabric's world render callback; no beacon blocks,
 * block entities, server packets, or world edits are involved.
 */
public final class BeamKillEffect {
    private static final long DURATION_MS = 3000L;
    private static final int MAX_ACTIVE = 5;
    private static final int[] PALETTE = {
            0xFF55F5, // pink
            0xFF544F, // red
            0xFFB349, // orange
            0xF8EA65, // yellow
            0x5CEB86, // green
            0x52E4EA, // cyan
            0x619CFF, // blue
            0xB97BFF, // purple
            0xF6F9FF  // white
    };

    private static final Random RANDOM = new Random();
    private static final List<Beam> ACTIVE = new ArrayList<>();

    private BeamKillEffect() {}

    private static final class Beam {
        final ClientWorld world;
        final BlockPos position;
        final int color;
        final long expiresAt;

        Beam(ClientWorld world, BlockPos position, int color, long expiresAt) {
            this.world = world;
            this.position = position;
            this.color = color;
            this.expiresAt = expiresAt;
        }
    }

    /** Register one callback for the lifetime of the mod; rendering uses only active effects. */
    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (ACTIVE.isEmpty()) return;
            MatrixStack matrices = context.matrixStack();
            VertexConsumerProvider consumers = context.consumers();
            if (matrices == null || consumers == null || context.world() == null) return;

            Vec3d camera = context.camera().getPos();
            long nowMs = System.currentTimeMillis();
            for (Beam beam : ACTIVE) {
                if (beam.world != context.world() || nowMs >= beam.expiresAt) continue;
                if (beam.position.getSquaredDistance(camera.x, camera.y, camera.z) > 128.0 * 128.0) continue;

                matrices.push();
                try {
                    // The built-in beacon renderer draws the beam around local (0.5, 0.5).
                    // Model coordinates must be relative to the camera for this event.
                    matrices.translate(
                            beam.position.getX() - camera.x,
                            beam.position.getY() - camera.y,
                            beam.position.getZ() - camera.z);

                    BeaconBlockEntityRenderer.renderBeam(
                            matrices,
                            consumers,
                            BeaconBlockEntityRenderer.BEAM_TEXTURE,
                            context.tickCounter().getTickDelta(false),
                            1.0f,
                            context.world().getTime(),
                            0,
                            256,
                            beam.color,
                            0.2f,
                            0.25f);
                } finally {
                    matrices.pop();
                }
            }
        });
    }

    public static void spawn(ClientWorld world, Vec3d pos, long nowMs) {
        if (world == null || pos == null) return;
        if (ACTIVE.size() >= MAX_ACTIVE) ACTIVE.remove(0);
        BlockPos blockPos = BlockPos.ofFloored(pos);
        int color = 0xFF000000 | PALETTE[RANDOM.nextInt(PALETTE.length)];
        ACTIVE.add(new Beam(world, blockPos, color, nowMs + DURATION_MS));

        // Restrained thunder crack + beacon activation for a bright electrical strike.
        KillEffectSounds.play(world,pos.x, pos.y, pos.z,
                resolveSound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER),
                SoundCategory.PLAYERS, 0.70f, 1.27f, false);
        KillEffectSounds.play(world,pos.x, pos.y, pos.z,
                resolveSound(SoundEvents.BLOCK_BEACON_ACTIVATE),
                SoundCategory.PLAYERS, 0.60f, 1.38f, false);
    }

    public static void tick(ClientWorld world, long nowMs) {
        Iterator<Beam> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            Beam beam = iterator.next();
            if (beam.world != world) {
                iterator.remove();
                continue;
            }
            if (nowMs >= beam.expiresAt) {
                // Play Beacon Deactivate exactly once as the three-second beam vanishes.
                KillEffectSounds.play(world,beam.position.getX() + 0.5,
                        beam.position.getY() + 0.5,
                        beam.position.getZ() + 0.5,
                        resolveSound(SoundEvents.BLOCK_BEACON_DEACTIVATE),
                        SoundCategory.PLAYERS, 0.88f, 1.10f, false);
                iterator.remove();
            }
        }
    }

    private static SoundEvent resolveSound(Object sound) {
        if (sound instanceof SoundEvent event) return event;
        if (sound instanceof RegistryEntry<?> entry && entry.value() instanceof SoundEvent event) return event;
        throw new IllegalArgumentException("Unknown sound event type: " + sound);
    }
}
