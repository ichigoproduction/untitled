package untitled.untitled.client;

import net.fabricmc.api.ClientModInitializer;

public class UntitledClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EditHud.register();
        PartyHud.init();
        FoodStack.init();
        SyringeCooldownHud.init();
        SkillHud.init();
        ItemModelCache.init();
        ItemModelInspector.init();
        ItemModelOverrides.init();
        ItemModelRuntime.init();
        ItemSoundInspector.init();
    }
}
