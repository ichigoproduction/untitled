package untitled.untitled.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;

public final class NewSwap {
    private static final String SOURCE_ITEM_NAME = "연";
    private static final String MACE_ITEM_NAME = "슈레더";

    private static boolean initialized = false;
    private static boolean attackPressedLastTick = false;
    private static int restoreSlot = PlayerInventory.NOT_FOUND;

    private NewSwap() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ClientTickEvents.START_CLIENT_TICK.register(NewSwap::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(NewSwap::onEndTick);
    }

    private static void onStartTick(MinecraftClient client) {
        boolean attackPressed = client != null
                && client.options != null
                && client.options.attackKey.isPressed();

        if (client == null
                || client.player == null
                || client.currentScreen != null) {
            attackPressedLastTick = attackPressed;
            return;
        }

        if (attackPressed && !attackPressedLastTick) {
            prepareSwap(client);
        }

        attackPressedLastTick = attackPressed;
    }

    private static void onEndTick(MinecraftClient client) {
        restoreOriginalSlot(client);
    }

    private static void prepareSwap(MinecraftClient client) {
        restoreOriginalSlot(client);

        if (client.player == null || client.getNetworkHandler() == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        if (!MaceItem.shouldDealAdditionalDamage(player)) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        int heldSlot = inventory.selectedSlot;
        if (!PlayerInventory.isValidHotbarIndex(heldSlot)) {
            return;
        }

        ItemStack heldStack = inventory.getStack(heldSlot);
        if (!matches(heldStack, Items.PRISMARINE_SHARD, SOURCE_ITEM_NAME)) {
            return;
        }

        int maceSlot = findNamedMaceSlot(inventory);
        if (!PlayerInventory.isValidHotbarIndex(maceSlot)
                || maceSlot == heldSlot) {
            return;
        }

        restoreSlot = heldSlot;
        selectSlot(client, inventory, maceSlot);
    }

    private static void restoreOriginalSlot(MinecraftClient client) {
        int slot = restoreSlot;
        restoreSlot = PlayerInventory.NOT_FOUND;

        if (client == null
                || client.player == null
                || client.getNetworkHandler() == null
                || !PlayerInventory.isValidHotbarIndex(slot)) {
            return;
        }

        selectSlot(client, client.player.getInventory(), slot);
    }

    private static int findNamedMaceSlot(PlayerInventory inventory) {
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            if (matches(inventory.getStack(slot), Items.MACE, MACE_ITEM_NAME)) {
                return slot;
            }
        }
        return PlayerInventory.NOT_FOUND;
    }

    private static boolean matches(ItemStack stack, Item item, String exactName) {
        return stack != null
                && !stack.isEmpty()
                && stack.isOf(item)
                && exactName.equals(stack.getName().getString());
    }

    private static void selectSlot(
            MinecraftClient client,
            PlayerInventory inventory,
            int slot
    ) {
        inventory.setSelectedSlot(slot);
        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }
}
