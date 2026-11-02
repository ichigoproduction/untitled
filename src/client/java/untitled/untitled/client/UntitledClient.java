package untitled.untitled.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UntitledClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("untitled/startup");
    private static int hudScreenDelay = -1;

    @Override
    public void onInitializeClient() {
        LOGGER.info("untitled client initialization started");
        EditHud.register();
        // Wait until the chat screen has finished closing after command execution.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (hudScreenDelay < 0) return;
            if (hudScreenDelay-- > 0) return;
            hudScreenDelay = -1;
            if (client.player != null && client.world != null) {
                client.setScreen(new HudSettingsScreen(null));
            }
        });
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) ->
            dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("152").executes(ctx -> {
                hudScreenDelay = 2;
                return 1;
            }))
        );
        TitleHud.init();
        Optimizer.init();
        Glow.init();
        KillEffect.register();
        SkinChanger.init();
        PartyHud.init();
        FoodStack.init();
        SyringeCooldownHud.init();
        SkillHud.init();
        ItemModelCache.init();
        GhostItemPreview.init();
        ItemModelOverrides.init();
        ItemModelRuntime.init();
        LOGGER.info("untitled client initialization finished: HUDs, commands and models registered");
    }
}
