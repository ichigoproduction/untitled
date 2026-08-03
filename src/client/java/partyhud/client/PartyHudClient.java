package partyhud.client;

import net.fabricmc.api.ClientModInitializer;

public final class PartyHudClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PartyHudEditor.register();
        PartyHud.init();
    }
}
