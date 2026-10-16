package untitled.untitled.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;

import java.util.Arrays;
import java.util.Locale;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * An ephemeral render-only overlay for the local player's 36 regular inventory slots.
 * Never calls PlayerInventory.setStack, ScreenHandler.onSlotClick or sends slot packets.
 */
public final class GhostItemPreview {
    private static final int PLAYER_COUNT = 36;
    private static final int CHEST_COUNT = 54;
    private static final ItemStack[] SLOTS = new ItemStack[PLAYER_COUNT];
    // Shared across chest-like UIs; never saved or transmitted to a server.
    private static final ItemStack[] CHEST = new ItemStack[CHEST_COUNT];
    private static ItemStack cursor = ItemStack.EMPTY;
    private static ClientWorld world;
    private static boolean started;

    static {
        Arrays.fill(SLOTS, ItemStack.EMPTY);
        Arrays.fill(CHEST, ItemStack.EMPTY);
    }

    private GhostItemPreview() {}

    public static void init() {
        if (started) return;
        started = true;
        ClientTickEvents.END_CLIENT_TICK.register(GhostItemPreview::tick);
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return literal("get")
                .then(literal("clear").executes(ctx -> {
                    clear();
                    ctx.getSource().sendFeedback(Text.literal("Ghost previews cleared."));
                    return 1;
                }))
                .then(argument("name", StringArgumentType.string())
                        .suggests((ctx, builder) -> {
                            String prefix = builder.getRemaining().toLowerCase(Locale.ROOT);
                            for (String name : ItemModelOverrides.previewNames()) {
                                String option = name.contains(" ") ? "\"" + name + "\"" : name;
                                if (name.toLowerCase(Locale.ROOT).startsWith(prefix.replace("\"", ""))) {
                                    builder.suggest(option);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> give(ctx.getSource(),
                                StringArgumentType.getString(ctx, "name"), 1))
                        .then(argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> give(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"),
                                        IntegerArgumentType.getInteger(ctx, "count")))));
    }

    private static int give(FabricClientCommandSource source, String name, int count) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            source.sendError(Text.literal("[152] iModel • Join a world first."));
            return 0;
        }
        ItemStack stack = ItemModelOverrides.previewStack(name);
        if (stack.isEmpty()) {
            source.sendError(Text.literal("[152] iModel • Not found: " + name));
            return 0;
        }
        int slot = firstFreeSlot(client.player.getInventory());
        String destination;
        if (slot >= 0) {
            SLOTS[slot] = stack.copyWithCount(count);
            destination = "player inventory";
        } else {
            int chest = firstFreeChest();
            if (chest < 0) {
                source.sendError(Text.literal("[152] iModel • Ghost inventory and chest storage are full."));
                return 0;
            }
            CHEST[chest] = stack.copyWithCount(count);
            destination = "ghost chest " + (chest + 1) + "/54 (open a chest to view)";
        }
        source.sendFeedback(Text.literal(name + " x" + count + " → " + destination));
        return 1;
    }

    public static void clear() {
        Arrays.fill(SLOTS, ItemStack.EMPTY);
        Arrays.fill(CHEST, ItemStack.EMPTY);
        cursor = ItemStack.EMPTY;
    }

    private static void tick(MinecraftClient client) {
        if (client.world != world) {
            world = client.world;
            clear();
        }
        if (client.player == null || client.world == null) {
            clear();
            return;
        }
        PlayerInventory inv = client.player.getInventory();
        for (int i = 0; i < SLOTS.length; i++) {
            if (!inv.getStack(i).isEmpty()) SLOTS[i] = ItemStack.EMPTY;
        }
        // Return the ghost cursor to free virtual storage on closing a GUI.
        // If all slots are occupied, retain the cursor for the next GUI.
        if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>)
                && !cursor.isEmpty()) {
            int empty = firstFreeSlot(inv);
            if (empty >= 0) {
                SLOTS[empty] = cursor;
                cursor = ItemStack.EMPTY;
            } else {
                int chest = firstFreeChest();
                if (chest >= 0) {
                    CHEST[chest] = cursor;
                    cursor = ItemStack.EMPTY;
                }
            }
        }
    }

    private static int firstFreeSlot(PlayerInventory inventory) {
        int selected = inventory.selectedSlot;
        if (selected >= 0 && selected < 9 && available(inventory, selected)) return selected;
        for (int i = 0; i < SLOTS.length; i++) {
            if (available(inventory, i)) return i;
        }
        return -1;
    }

    private static boolean available(PlayerInventory inventory, int index) {
        return index >= 0 && index < PLAYER_COUNT
                && inventory.getStack(index).isEmpty() && SLOTS[index].isEmpty();
    }

    private static int chestCapacity(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler chest) return chest.getRows() * 9;
        if (handler instanceof ShulkerBoxScreenHandler) return 27;
        if (handler instanceof HopperScreenHandler) return 5;
        if (handler instanceof Generic3x3ContainerScreenHandler) return 9;
        // Functional GUIs (crafting, furnaces, trades, anvils) are deliberately excluded.
        return 0;
    }

    /** Player inventory [0..35], virtual chest [36..89]. */
    private static int ghostSlotIndex(Slot slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (slot == null || client.player == null) return -1;
        if (slot.inventory == client.player.getInventory()) {
            int index = slot.getIndex();
            return index >= 0 && index < PLAYER_COUNT ? index : -1;
        }
        ScreenHandler handler = client.player.currentScreenHandler;
        int cap = Math.min(CHEST_COUNT, chestCapacity(handler));
        if (slot.id < 0 || slot.id >= cap) return -1;
        int index = slot.getIndex();
        return index >= 0 && index < cap ? PLAYER_COUNT + index : -1;
    }

    private static ItemStack virtualAt(int index) {
        if (index < 0) return ItemStack.EMPTY;
        return index < PLAYER_COUNT ? SLOTS[index] : CHEST[index - PLAYER_COUNT];
    }

    private static void setVirtual(int index, ItemStack stack) {
        if (index < PLAYER_COUNT) SLOTS[index] = stack;
        else CHEST[index - PLAYER_COUNT] = stack;
    }

    private static Slot visibleChestSlot(int chestIndex) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return null;
        ScreenHandler handler = client.player.currentScreenHandler;
        for (Slot slot : handler.slots) {
            if (ghostSlotIndex(slot) == PLAYER_COUNT + chestIndex) return slot;
        }
        return null;
    }

    private static boolean freeChest(int index) {
        if (index < 0 || index >= CHEST_COUNT || !CHEST[index].isEmpty()) return false;
        Slot visible = visibleChestSlot(index);
        return visible == null || visible.getStack().isEmpty();
    }

    private static int firstFreeChest() {
        for (int i = 0; i < CHEST_COUNT; i++) {
            if (freeChest(i)) return i;
        }
        return -1;
    }

    public static ItemStack forSlot(Slot slot) {
        int index = ghostSlotIndex(slot);
        if (index < 0 || !slot.getStack().isEmpty()) return ItemStack.EMPTY;
        return virtualAt(index);
    }

    public static ItemStack forHotbar(int index, ItemStack actual) {
        if (index < 0 || index >= 9 || !actual.isEmpty()) return ItemStack.EMPTY;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null
                || !client.player.getInventory().getStack(index).isEmpty()) return ItemStack.EMPTY;
        return SLOTS[index];
    }

    public static ItemStack forFirstPerson(Hand hand, ItemStack actual,
                                          AbstractClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (player != client.player || hand != Hand.MAIN_HAND || !actual.isEmpty()
                || client.player == null || client.world == null) return actual;
        int index = client.player.getInventory().selectedSlot;
        return forHotbar(index, actual).isEmpty() ? actual : SLOTS[index];
    }

    public static ItemStack forThirdPerson(Arm arm, ItemStack actual,
                                          AbstractClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (player != client.player || client.player == null || client.world == null
                || arm != player.getMainArm() || !actual.isEmpty()
                || !player.getMainHandStack().isEmpty()) return actual;
        int index = client.player.getInventory().selectedSlot;
        ItemStack ghost = forHotbar(index, actual);
        return ghost.isEmpty() ? actual : ghost;
    }

    public static boolean isGhostStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (cursor == stack) return true;
        for (ItemStack ghost : SLOTS) {
            if (stack == ghost) return true;
        }
        for (ItemStack ghost : CHEST) {
            if (stack == ghost) return true;
        }
        return false;
    }

    public static boolean hasCursor() {
        return !cursor.isEmpty();
    }

    public static ItemStack cursorStack() {
        return cursor;
    }

    /**
     * Consume only virtual operations. Never modify a real stack or forward
     * ghost movements to the server. Real-item slots always win.
     */
    public static boolean click(Slot slot, int button, SlotActionType action) {
        int index = ghostSlotIndex(slot);
        ItemStack ghost = forSlot(slot);
        if (ghost.isEmpty() && cursor.isEmpty()) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.currentScreenHandler == null) return true;
        if (!client.player.currentScreenHandler.getCursorStack().isEmpty()) return true;
        if (slot == null) {
            if (action == SlotActionType.PICKUP || action == SlotActionType.THROW) {
                cursor = ItemStack.EMPTY;
            }
            return true;
        }
        if (index < 0 || !slot.getStack().isEmpty()) return true;
        if (!cursor.isEmpty() && action != SlotActionType.PICKUP) return true;

        if (action == SlotActionType.THROW) {
            if (!ghost.isEmpty()) {
                setVirtual(index, button == 0 && ghost.getCount() > 1
                        ? ghost.copyWithCount(ghost.getCount() - 1) : ItemStack.EMPTY);
            }
            return true;
        }

        if (action == SlotActionType.QUICK_MOVE) {
            if (!ghost.isEmpty()) {
                int dest = quickMoveTarget(index, client.player.getInventory());
                if (dest >= 0) {
                    setVirtual(index, ItemStack.EMPTY);
                    setVirtual(dest, ghost);
                }
            }
            return true;
        }

        if (action == SlotActionType.SWAP) {
            if (button >= 0 && button < 9 && index != button
                    && client.player.getInventory().getStack(button).isEmpty()) {
                ItemStack other = SLOTS[button];
                SLOTS[button] = ghost;
                setVirtual(index, other);
            }
            return true;
        }

        if (action != SlotActionType.PICKUP || (button != 0 && button != 1)) return true;
        if (cursor.isEmpty()) {
            if (ghost.isEmpty()) return false;
            if (button == 1 && ghost.getCount() > 1) {
                int amount = (ghost.getCount() + 1) / 2;
                cursor = ghost.copyWithCount(amount);
                setVirtual(index, ghost.copyWithCount(ghost.getCount() - amount));
            } else {
                cursor = ghost;
                setVirtual(index, ItemStack.EMPTY);
            }
        } else if (ghost.isEmpty()) {
            if (button == 1 && cursor.getCount() > 1) {
                setVirtual(index, cursor.copyWithCount(1));
                cursor = cursor.copyWithCount(cursor.getCount() - 1);
            } else {
                setVirtual(index, cursor);
                cursor = ItemStack.EMPTY;
            }
        } else if (ItemStack.areItemsAndComponentsEqual(ghost, cursor)
                && ghost.getCount() < ghost.getMaxCount()) {
            int amount = Math.min(button == 1 ? 1 : cursor.getCount(),
                    ghost.getMaxCount() - ghost.getCount());
            setVirtual(index, ghost.copyWithCount(ghost.getCount() + amount));
            cursor = cursor.getCount() > amount
                    ? cursor.copyWithCount(cursor.getCount() - amount) : ItemStack.EMPTY;
        } else if (button == 0) {
            setVirtual(index, cursor);
            cursor = ghost;
        }
        return true;
    }

    private static int quickMoveTarget(int from, PlayerInventory inventory) {
        if (from < PLAYER_COUNT) {
            MinecraftClient client = MinecraftClient.getInstance();
            int visibleCount = client.player == null
                    ? 0 : Math.min(CHEST_COUNT, chestCapacity(client.player.currentScreenHandler));
            // Player -> chest, if a chest is currently open.
            for (int i = 0; i < visibleCount; i++) {
                if (freeChest(i)) return PLAYER_COUNT + i;
            }
            for (int i = 0; i < PLAYER_COUNT; i++) {
                if (i != from && available(inventory, i)) return i;
            }
        } else {
            // Chest -> player, never to a server container slot.
            for (int i = 0; i < PLAYER_COUNT; i++) {
                if (available(inventory, i)) return i;
            }
        }
        return -1;
    }
}
