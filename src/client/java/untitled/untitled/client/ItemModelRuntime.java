package untitled.untitled.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

public final class ItemModelRuntime {
    private static final ItemModelStateMachine STATE = new ItemModelStateMachine();
    private static boolean initialized = false;

    private ItemModelRuntime() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        ClientTickEvents.END_CLIENT_TICK.register(ItemModelRuntime::tick);
    }

    public static void onAttack(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            STATE.reset();
            return;
        }

        ItemStack held = client.player.getMainHandStack();
        if (held.isEmpty()) {
            return;
        }

        String name = held.getName().getString();
        STATE.onAttack(name, ItemModelOverrides.hasDrawnState(name));
    }

    public static boolean isDrawn(String targetName) {
        return STATE.isDrawn(targetName);
    }

    public static void reset() {
        STATE.reset();
    }

    private static void tick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
            STATE.reset();
            return;
        }

        ItemStack held = client.player.getMainHandStack();
        String name = held.isEmpty() ? null : held.getName().getString();
        STATE.observe(client.player.getInventory().selectedSlot, name);
    }
}
