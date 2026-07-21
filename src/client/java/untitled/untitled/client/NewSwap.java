package untitled.untitled.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.MaceItem;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.hit.EntityHitResult;

public final class NewSwap {
    private static final String SOURCE_ITEM_NAME = "연";
    private static final String MACE_ITEM_NAME = "슈레더";

    private static boolean initialized = false;
    private static boolean attackSwapActive = false;
    private static int sourceSlot = PlayerInventory.NOT_FOUND;

    private NewSwap() {
    }

    public static void init() {
        initialized = true;
    }

    public static void beforeAttack(MinecraftClient client) {
        restoreStaleSwap(client);

        if (!initialized
                || client == null
                || client.player == null
                || client.getNetworkHandler() == null
                || client.player.isSpectator()) {
            return;
        }

        if (!(client.crosshairTarget instanceof EntityHitResult entityHitResult)) {
            return;
        }

        Entity target = entityHitResult.getEntity();
        if (!(target instanceof LivingEntity livingTarget)
                || !livingTarget.isAlive()
                || target == client.player) {
            return;
        }

        ClientPlayerEntity player = client.player;
        if (!MaceItem.shouldDealAdditionalDamage(player)) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        int selectedSlot = inventory.selectedSlot;
        if (!PlayerInventory.isValidHotbarIndex(selectedSlot)) {
            return;
        }

        if (!matches(
                inventory.getStack(selectedSlot),
                Items.PRISMARINE_SHARD,
                SOURCE_ITEM_NAME
        )) {
            return;
        }

        int foundMaceSlot = findNamedMaceSlot(inventory);
        if (!PlayerInventory.isValidHotbarIndex(foundMaceSlot)
                || foundMaceSlot == selectedSlot) {
            return;
        }

        sourceSlot = selectedSlot;
        attackSwapActive = true;
        selectSlot(client, inventory, foundMaceSlot);
    }

    public static void afterAttack(MinecraftClient client) {
        if (!attackSwapActive) {
            return;
        }

        if (client == null
                || client.player == null
                || client.getNetworkHandler() == null) {
            clearState();
            return;
        }

        PlayerInventory inventory = client.player.getInventory();
        int slotToRestore = sourceSlot;
        boolean canRestore = PlayerInventory.isValidHotbarIndex(slotToRestore)
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

    private static void restoreStaleSwap(MinecraftClient client) {
        if (!attackSwapActive) {
            return;
        }

        afterAttack(client);
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
                && exactName.equals(stack.getName().getString().trim());
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
        attackSwapActive = false;
        sourceSlot = PlayerInventory.NOT_FOUND;
    }
}
