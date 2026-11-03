package untitled.untitled.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;

/**
 * Central sound gain for all 152 kill effects (including delayed cues).
 *
 * 0%   = no kill-effect sounds
 * 100% = original volumes
 * 200% = twice the original requested sound volume
 *
 * Does not alter vanilla/other mods' sounds or change Minecraft options.
 */
public final class KillEffectSounds {
    private KillEffectSounds() {}

    private static float volume(float original) {
        return original * (KillEffect.settingsSoundVolume() / 100.0f);
    }

    public static void play(ClientWorld world, double x, double y, double z,
                            Object sound, SoundCategory category,
                            float originalVolume, float pitch, boolean useDistance) {
        if (world == null) return;
        float volume = volume(originalVolume);
        if (volume <= 0.0f) return;
        SoundEvent event = resolve(sound);
        if (event != null) {
            world.playSound(x, y, z, event, category, volume, pitch, useDistance);
        }
    }

    public static void playMaster(Object sound, float originalVolume, float pitch) {
        float volume = volume(originalVolume);
        if (volume <= 0.0f) return;
        SoundEvent event = resolve(sound);
        MinecraftClient client = MinecraftClient.getInstance();
        if (event != null && client != null) {
            client.getSoundManager().play(PositionedSoundInstance.master(event, pitch, volume));
        }
    }

    private static SoundEvent resolve(Object sound) {
        if (sound instanceof SoundEvent event) return event;
        if (sound instanceof RegistryEntry<?> entry && entry.value() instanceof SoundEvent event) {
            return event;
        }
        return null;
    }
}
