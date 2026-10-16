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
    private static final ItemStack[] SLOTS = new ItemStack[36];
    private static ItemStack cursor = ItemStack.EMPTY;
    private static ClientWorld world;
    private static boolean started;

    static {
        Arrays.fill(SLOTS, ItemStack.EMPTY);
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
                    message(ctx.getSource(), "Ghost previews cleared.");
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
        if (slot < 0) {
            source.sendError(Text.literal("[152] iModel • No empty inventory slot."));
            return 0;
        }
        SLOTS[slot] = stack.copyWithCount(count);
        message(source, "Ghost preview: " + name + " x" + count + " (slot " + slot
                + ", client only)");
        return 1;
    }

    private static void message(FabricClientCommandSource source, String body) {
        source.sendFeedback(Text.literal("[152] iModel • " + body));
    }

    public static void clear() {
        Arrays.fill(SLOTS, ItemStack.EMPTY);
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
        // A virtual stack must never be left on the cursor after the GUI closes.
        if (!(client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>)
                && !cursor.isEmpty()) {
            int empty = firstFreeSlot(inv);
            if (empty >= 0) SLOTS[empty] = cursor;
            cursor = ItemStack.EMPTY;
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
        return inventory.getStack(index).isEmpty() && SLOTS[index].isEmpty();
    }

    private static int ghostSlotIndex(Slot slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (slot == null || client.player == null
                || slot.inventory != client.player.getInventory()) return -1;
        int index = slot.getIndex();
        return index >= 0 && index < SLOTS.length ? index : -1;
    }

    public static ItemStack forSlot(Slot slot) {
        int index = ghostSlotIndex(slot);
        if (index < 0 || !slot.getStack().isEmpty()) return ItemStack.EMPTY;
        return SLOTS[index];
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
        return false;
    }

    public static boolean hasCursor() {
        return !cursor.isEmpty();
    }

    public static ItemStack cursorStack() {
        return cursor;
    }

    /**
     * True means the click belongs to the virtual layer and must never be forwarded
     * to the network. Clicking real items while holding a ghost cursor is blocked.
     */
    public static boolean click(Slot slot, int button, SlotActionType action) {
        int index = ghostSlotIndex(slot);
        ItemStack ghost = index < 0 ? ItemStack.EMPTY : forSlot(slot);
        if (ghost.isEmpty() && cursor.isEmpty()) return false;

        // A real carried stack cannot interact with virtual slots.
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.player.currentScreenHandler == null) return true;
        if (!client.player.currentScreenHandler.getCursorStack().isEmpty()) return true;

        if (slot == null) {
            if (action == SlotActionType.PICKUP || action == SlotActionType.THROW) cursor = ItemStack.EMPTY;
            return true;
        }
        if (index < 0 || !slot.getStack().isEmpty()) return true;
        if (action == SlotActionType.THROW) {
            if (!ghost.isEmpty()) {
                if (button == 0 && ghost.getCount() > 1) {
                    SLOTS[index] = ghost.copyWithCount(ghost.getCount() - 1);
                } else SLOTS[index] = ItemStack.EMPTY;
            }
            return true;
        }
        if (action == SlotActionType.QUICK_MOVE) {
            if (!ghost.isEmpty()) {
                PlayerInventory inv = client.player.getInventory();
                int next = -1;
                // Shift-click moves to the other inventory section, if possible.
                for (int i = index < 9 ? 9 : 0; i < (index < 9 ? 36 : 9); i++) {
                    if (available(inv, i)) { next = i; break; }
                }
                if (next < 0) {
                    for (int i = 0; i < SLOTS.length; i++) {
                        if (i != index && available(inv, i)) { next = i; break; }
                    }
                }
                if (next >= 0) {
                    SLOTS[index] = ItemStack.EMPTY;
                    SLOTS[next] = ghost;
                }
            }
            return true;
        }
        if (action == SlotActionType.SWAP) {
            if (button >= 0 && button < 9 && index != button
                    && client.player.getInventory().getStack(button).isEmpty()) {
                ItemStack previous = SLOTS[button];
                SLOTS[button] = ghost;
                SLOTS[index] = previous;
            }
            return true;
        }
        if (action != SlotActionType.PICKUP || (button != 0 && button != 1)) return true;

        if (cursor.isEmpty()) {
            if (ghost.isEmpty()) return false;
            if (button == 1 && ghost.getCount() > 1) {
                int taken = (ghost.getCount() + 1) / 2;
                cursor = ghost.copyWithCount(taken);
                SLOTS[index] = ghost.copyWithCount(ghost.getCount() - taken);
            } else {
                cursor = ghost;
                SLOTS[index] = ItemStack.EMPTY;
            }
        } else if (ghost.isEmpty()) {
            if (button == 1 && cursor.getCount() > 1) {
                SLOTS[index] = cursor.copyWithCount(1);
                cursor = cursor.copyWithCount(cursor.getCount() - 1);
            } else {
                SLOTS[index] = cursor;
                cursor = ItemStack.EMPTY;
            }
        } else if (ItemStack.areItemsAndComponentsEqual(ghost, cursor)
                && ghost.getCount() < ghost.getMaxCount()) {
            int moved = Math.min(button == 1 ? 1 : cursor.getCount(),
                    ghost.getMaxCount() - ghost.getCount());
            SLOTS[index] = ghost.copyWithCount(ghost.getCount() + moved);
            cursor = cursor.getCount() > moved
                    ? cursor.copyWithCount(cursor.getCount() - moved) : ItemStack.EMPTY;
        } else if (button == 0) {
            SLOTS[index] = cursor;
            cursor = ghost;
        }
        return true;
    }
}
