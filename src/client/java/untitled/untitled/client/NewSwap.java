package untitled.untitled.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.hit.EntityHitResult;

public final class NewSwap {
    private static final String SOURCE_ITEM_NAME = "연";
    private static final String MACE_ITEM_NAME = "슈레더";
    private static final float MIN_FALL_DISTANCE = 1.5F;

    private static boolean initialized = false;
    private static boolean attackSwapActive = false;
    private static int sourceSlot = PlayerInventory.NOT_FOUND;
    private static int restoreDelayTicks = 0;

    private NewSwap() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ClientTickEvents.END_CLIENT_TICK.register(NewSwap::onEndClientTick);
    }

    public static void onLeftMousePress(MinecraftClient client) {
        restoreSource(client);

        if (!initialized
                || client == null
                || client.player == null
                || client.getNetworkHandler() == null
                || client.currentScreen != null
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
        if (!isSmashReady(player)) {
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
        restoreDelayTicks = 1;
        selectSlot(client, inventory, foundMaceSlot);
    }

    private static boolean isSmashReady(ClientPlayerEntity player) {
        return !player.isOnGround()
                && player.getVelocity().y < 0.0
                && player.fallDistance > MIN_FALL_DISTANCE;
    }

    private static void onEndClientTick(MinecraftClient client) {
        if (!attackSwapActive) {
            return;
        }

        if (restoreDelayTicks > 0) {
            restoreDelayTicks--;
            return;
        }

        restoreSource(client);
    }

    private static void restoreSource(MinecraftClient client) {
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
        restoreDelayTicks = 0;
    }
}
