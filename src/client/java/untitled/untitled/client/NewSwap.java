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
    private static boolean swapActive = false;
    private static int sourceSlot = PlayerInventory.NOT_FOUND;
    private static int maceSlot = PlayerInventory.NOT_FOUND;

    private NewSwap() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ClientTickEvents.START_CLIENT_TICK.register(NewSwap::tick);
    }

    private static void tick(MinecraftClient client) {
        if (client == null
                || client.player == null
                || client.getNetworkHandler() == null) {
            clearState();
            return;
        }

        ClientPlayerEntity player = client.player;
        PlayerInventory inventory = player.getInventory();

        if (client.currentScreen != null) {
            restoreSource(client, inventory);
            return;
        }

        if (swapActive) {
            updateActiveSwap(client, player, inventory);
            return;
        }

        beginSwapIfReady(client, player, inventory);
    }

    private static void beginSwapIfReady(
            MinecraftClient client,
            ClientPlayerEntity player,
            PlayerInventory inventory
    ) {
        int selectedSlot = inventory.selectedSlot;
        if (!PlayerInventory.isValidHotbarIndex(selectedSlot)) {
            return;
        }

        ItemStack selectedStack = inventory.getStack(selectedSlot);
        if (!matches(selectedStack, Items.PRISMARINE_SHARD, SOURCE_ITEM_NAME)) {
            return;
        }

        if (!MaceItem.shouldDealAdditionalDamage(player)) {
            return;
        }

        int foundMaceSlot = findNamedMaceSlot(inventory);
        if (!PlayerInventory.isValidHotbarIndex(foundMaceSlot)
                || foundMaceSlot == selectedSlot) {
            return;
        }

        sourceSlot = selectedSlot;
        maceSlot = foundMaceSlot;
        swapActive = true;
        selectSlot(client, inventory, maceSlot);
    }

    private static void updateActiveSwap(
            MinecraftClient client,
            ClientPlayerEntity player,
            PlayerInventory inventory
    ) {
        if (!isTrackedSourceValid(inventory) || !isTrackedMaceValid(inventory)) {
            restoreSource(client, inventory);
            return;
        }

        if (MaceItem.shouldDealAdditionalDamage(player)) {
            if (inventory.selectedSlot != maceSlot) {
                selectSlot(client, inventory, maceSlot);
            }
            return;
        }

        restoreSource(client, inventory);
    }

    private static void restoreSource(
            MinecraftClient client,
            PlayerInventory inventory
    ) {
        int slotToRestore = sourceSlot;
        boolean canRestore = swapActive
                && PlayerInventory.isValidHotbarIndex(slotToRestore)
                && matches(
                        inventory.getStack(slotToRestore),
                        Items.PRISMARINE_SHARD,
                        SOURCE_ITEM_NAME
                );

        clearState();

        if (canRestore && inventory.selectedSlot != slotToRestore) {
            selectSlot(client, inventory, slotToRestore);
        }
    }

    private static boolean isTrackedSourceValid(PlayerInventory inventory) {
        return PlayerInventory.isValidHotbarIndex(sourceSlot)
                && matches(
                        inventory.getStack(sourceSlot),
                        Items.PRISMARINE_SHARD,
                        SOURCE_ITEM_NAME
                );
    }

    private static boolean isTrackedMaceValid(PlayerInventory inventory) {
        return PlayerInventory.isValidHotbarIndex(maceSlot)
                && matches(inventory.getStack(maceSlot), Items.MACE, MACE_ITEM_NAME);
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

    private static void clearState() {
        swapActive = false;
        sourceSlot = PlayerInventory.NOT_FOUND;
        maceSlot = PlayerInventory.NOT_FOUND;
    }
}
