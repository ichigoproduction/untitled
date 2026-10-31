package hudonly.client;

import net.fabricmc.api.ClientModInitializer;

public final class HudOnlyClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EditHud.register();
        FoodStack.init();
        SyringeCooldownHud.init();
    }
}
