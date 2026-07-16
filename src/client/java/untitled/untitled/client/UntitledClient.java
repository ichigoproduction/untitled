package untitled.untitled.client;

import net.fabricmc.api.ClientModInitializer;

public class UntitledClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EditHud.init();
        ContentTimer.init();
        PartyHud.init();
    }
}
