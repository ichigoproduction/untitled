package untitled.untitled.client;

import net.fabricmc.api.ClientModInitializer;

public class UntitledClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EditHud.register();
        ContentTimer.init();
        PartyHud.init();
        CameraControl.init();
    }
}
