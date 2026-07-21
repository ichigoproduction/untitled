package untitled.untitled.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class NewSwapClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("untitled/NewSwapBootstrap");
    private static final String PREFIX = "[NewSwapDebug] ";

    private static String startupState = "not initialized";

    @Override
    public void onInitializeClient() {
        String version = getVersion();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("newswapcheck").executes(context -> {
                    sendChat("bootstrap loaded; version=" + version + "; state=" + startupState);
                    return 1;
                }))
        );

        try {
            NewSwap.init();
            startupState = "NewSwap.init OK";
            LOGGER.info("{}standalone entrypoint initialized; version={}; state={}", PREFIX, version, startupState);
        } catch (Throwable throwable) {
            startupState = "NewSwap.init FAILED: "
                    + throwable.getClass().getName()
                    + ": "
                    + String.valueOf(throwable.getMessage());
            LOGGER.error("{}standalone entrypoint failed; version={}; state={}", PREFIX, version, startupState, throwable);
        }

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> sendChat(
                        "loaded; version=" + version
                                + "; state=" + startupState
                                + "; commands=/newswapcheck,/newswapdebug"
                ))
        );
    }

    private static String getVersion() {
        return FabricLoader.getInstance()
                .getModContainer("untitled")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static void sendChat(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(PREFIX + message), false);
        }
        LOGGER.info("{}{}", PREFIX, message);
    }
}
