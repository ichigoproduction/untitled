package untitled.untitled.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Local player's five-second multicolored note celebration after a kill.
 * Two short rising "di-ri-ri-ri-ri-ring" phrases use only vanilla sounds.
 */
public final class NoteKillEffect {
    private static final long DURATION_MS = 5000L;
    private static final long SECOND_PHRASE_MS = 2000L;
    private static final long NOTE_INTERVAL_MS = 108L;
    private static final int MAX_ACTIVE = 2;
    private static final float[] PITCHES = {1.12f, 1.26f, 1.41f, 1.59f, 1.78f, 1.89f, 2.00f};
    private static final Random RANDOM = new Random();
    private static final List<NoteBurst> ACTIVE = new ArrayList<>();

    private NoteKillEffect() {}

    private static final class NoteBurst {
        final ClientWorld world;
        final long started;
        final boolean[][] played = new boolean[2][PITCHES.length];
        int visualTick;

        NoteBurst(ClientWorld world, long started) {
            this.world = world;
            this.started = started;
        }
    }

    public static void spawn(ClientWorld world, long nowMs) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (world == null || client.player == null || client.world != world) return;
        if (ACTIVE.size() >= MAX_ACTIVE) ACTIVE.remove(0);
        ACTIVE.add(new NoteBurst(world, nowMs));
    }

    public static void tick(ClientWorld world, long nowMs) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Iterator<NoteBurst> it = ACTIVE.iterator();
        while (it.hasNext()) {
            NoteBurst burst = it.next();
            long age = nowMs - burst.started;
            if (world != burst.world || mc.player == null || age < 0 || age >= DURATION_MS) {
                it.remove();
                continue;
            }

            Vec3d head = mc.player.getPos().add(0.0, mc.player.getHeight() + 0.43, 0.0);
            if ((burst.visualTick++ & 1) == 0) {
                // ParticleTypes.NOTE uses the first velocity parameter as a hue (0..1).
                // Spread notes over and around the killer's moving head, not the victim.
                for (int i = 0; i < 4; i++) {
                    double a = RANDOM.nextDouble() * Math.PI * 2.0;
                    double radius = 0.22 + RANDOM.nextDouble() * 0.58;
                    double hue = RANDOM.nextDouble();
                    world.addParticle(ParticleTypes.NOTE,
                            head.x + Math.cos(a) * radius,
                            head.y + RANDOM.nextDouble() * 0.62,
                            head.z + Math.sin(a) * radius,
                            hue, 0.0, 0.0);
                }
            }

            for (int phrase = 0; phrase < 2; phrase++) {
                long start = (phrase == 0) ? 0 : SECOND_PHRASE_MS;
                for (int note = 0; note < PITCHES.length; note++) {
                    long due = start + note * NOTE_INTERVAL_MS;
                    if (!burst.played[phrase][note] && age >= due) {
                        burst.played[phrase][note] = true;
                        // Sparkly, rising bell notes in a brighter register, repeated twice.
                        world.playSound(mc.player.getX(), mc.player.getY() + 1.3, mc.player.getZ(),
                                resolveSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL), SoundCategory.PLAYERS,
                                note == PITCHES.length - 1 ? 0.88f : 0.55f,
                                PITCHES[note], false);
                        if (note == PITCHES.length - 1) {
                            world.playSound(mc.player.getX(), mc.player.getY() + 1.3, mc.player.getZ(),
                                    resolveSound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME), SoundCategory.PLAYERS,
                                    0.28f, 1.68f, false);
                        }
                    }
                }
            }
        }
    }
    // In 1.21.4 SoundEvents fields can be RegistryEntry<SoundEvent> or SoundEvent.
    // ClientWorld.playSound(x, y, z, ...) needs the unwrapped SoundEvent.
    private static SoundEvent resolveSound(Object sound) {
        if (sound instanceof SoundEvent event) return event;
        if (sound instanceof RegistryEntry<?> entry && entry.value() instanceof SoundEvent event) return event;
        throw new IllegalArgumentException("Unknown sound event type: " + sound);
    }

}
