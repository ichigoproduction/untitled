package untitled.untitled.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundInstanceListener;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.text.Text;

import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class ItemSoundInspector {
    private static final SoundInstanceListener LISTENER = ItemSoundInspector::onSoundPlayed;

    private static boolean initialized = false;
    private static boolean enabled = false;

    private ItemSoundInspector() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        MinecraftClient.getInstance().getSoundManager().registerListener(LISTENER);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        literal("isoundinspect")
                                .executes(context -> toggle(context.getSource()))
                )
        );
    }

    private static int toggle(FabricClientCommandSource source) {
        enabled = !enabled;
        source.sendFeedback(Text.literal("isoundinspect : " + (enabled ? "ON" : "OFF")));
        return 1;
    }

    private static void onSoundPlayed(
            SoundInstance sound,
            WeightedSoundSet soundSet,
            float range
    ) {
        if (!enabled) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        List<String> lines = ItemSoundInspectorFormatter.format(
                sound.getId().toString(),
                sound.getCategory().getName(),
                sound.getVolume(),
                sound.getPitch(),
                sound.getX(),
                sound.getY(),
                sound.getZ()
        );

        client.execute(() -> {
            if (!enabled || client.player == null) {
                return;
            }
            for (String line : lines) {
                client.player.sendMessage(Text.literal(line), false);
            }
        });
    }
}
