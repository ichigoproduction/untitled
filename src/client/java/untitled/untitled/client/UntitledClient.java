package untitled.untitled.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UntitledClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("untitled/startup");

    @Override
    public void onInitializeClient() {
        LOGGER.info("untitled client initialization started");
        EditHud.register();
        TitleHud.init();
        Glow.init();
        SkinChanger.init();
        PartyHud.init();
        FoodStack.init();
        SyringeCooldownHud.init();
        SkillHud.init();
        ItemModelCache.init();
        GhostItemPreview.init();
        ItemModelInspector.init();
        ItemModelOverrides.init();
        ItemModelRuntime.init();
        ItemSoundInspector.init();
        LOGGER.info("untitled client initialization finished: HUDs, commands, models and inspectors registered");
    }
}
