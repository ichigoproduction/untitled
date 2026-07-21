package untitled.untitled.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
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
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.StringJoiner;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class NewSwap {
    private static final Logger LOGGER = LoggerFactory.getLogger("untitled/NewSwap");

    private static final String LOG_PREFIX = "[NewSwapDebug] ";
    private static final String SOURCE_ITEM_NAME = "연";
    private static final String MACE_ITEM_NAME = "슈레더";
    private static final float MIN_FALL_DISTANCE = 1.5F;

    private static boolean initialized = false;
    private static boolean debugEnabled = true;
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
        registerCommands();

        LOGGER.info("{}initialized; debugEnabled=true; hook=Mouse#onMouseButton", LOG_PREFIX);
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("newswapdebug")
                        .executes(context -> {
                            dumpStatus(MinecraftClient.getInstance(), true);
                            return 1;
                        })
                        .then(literal("on").executes(context -> {
                            debugEnabled = true;
                            LOGGER.info("{}debug enabled by command", LOG_PREFIX);
                            sendChat(MinecraftClient.getInstance(), "debug enabled");
                            return 1;
                        }))
                        .then(literal("off").executes(context -> {
                            LOGGER.info("{}debug disabled by command", LOG_PREFIX);
                            debugEnabled = false;
                            sendChat(MinecraftClient.getInstance(), "debug disabled");
                            return 1;
                        })))
        );
    }

    public static void onRawMouseButton(
            MinecraftClient client,
            long window,
            int button,
            int action,
            int mods
    ) {
        if (action != GLFW.GLFW_PRESS) {
            return;
        }

        long expectedWindow = client == null ? -1L : client.getWindow().getHandle();
        debug(
                "raw mouse press captured: initialized={}, window={}, expectedWindow={}, button={}, action={}, mods={}",
                initialized,
                window,
                expectedWindow,
                button,
                action,
                mods
        );

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }

        if (client == null) {
            block(null, "MinecraftClient is null");
            return;
        }

        if (window != expectedWindow) {
            block(client, "window handle mismatch: actual=" + window + ", expected=" + expectedWindow);
            return;
        }

        onLeftMousePress(client);
    }

    private static void onLeftMousePress(MinecraftClient client) {
        restoreSource(client, "new left click started while an old swap was active");
        dumpStatus(client, false);

        if (!initialized) {
            block(client, "NewSwap.init() was not called");
            return;
        }
        if (client.player == null) {
            block(client, "client.player is null");
            return;
        }
        if (client.getNetworkHandler() == null) {
            block(client, "network handler is null");
            return;
        }
        if (client.currentScreen != null) {
            block(client, "screen is open: " + client.currentScreen.getClass().getSimpleName());
            return;
        }
        if (client.player.isSpectator()) {
            block(client, "player is spectator");
            return;
        }

        if (!(client.crosshairTarget instanceof EntityHitResult entityHitResult)) {
            block(client, "crosshair is not an entity: " + describeCrosshair(client));
            return;
        }

        Entity target = entityHitResult.getEntity();
        if (!(target instanceof LivingEntity livingTarget)) {
            block(client, "target is not LivingEntity: " + describeEntity(target));
            return;
        }
        if (!livingTarget.isAlive()) {
            block(client, "target is not alive: " + describeEntity(target));
            return;
        }
        if (target == client.player) {
            block(client, "target is the local player");
            return;
        }

        ClientPlayerEntity player = client.player;
        if (!isSmashReady(player)) {
            block(client, String.format(
                    Locale.ROOT,
                    "smash condition failed: onGround=%s, velocityY=%.4f, fallDistance=%.3f, requiredFallDistance>%.3f",
                    player.isOnGround(),
                    player.getVelocity().y,
                    player.fallDistance,
                    MIN_FALL_DISTANCE
            ));
            return;
        }

        PlayerInventory inventory = player.getInventory();
        int selectedSlot = inventory.selectedSlot;
        if (!PlayerInventory.isValidHotbarIndex(selectedSlot)) {
            block(client, "selected slot is outside hotbar: " + selectedSlot);
            return;
        }

        ItemStack heldStack = inventory.getStack(selectedSlot);
        if (!matches(heldStack, Items.PRISMARINE_SHARD, SOURCE_ITEM_NAME)) {
            block(client, "held item does not match source: slot=" + selectedSlot + ", " + describeStack(heldStack));
            return;
        }

        int foundMaceSlot = findNamedMaceSlot(inventory);
        if (!PlayerInventory.isValidHotbarIndex(foundMaceSlot)) {
            block(client, "named mace was not found in hotbar: " + describeHotbar(inventory));
            return;
        }
        if (foundMaceSlot == selectedSlot) {
            block(client, "source slot and mace slot are identical: " + selectedSlot);
            return;
        }

        sourceSlot = selectedSlot;
        attackSwapActive = true;
        restoreDelayTicks = 1;

        debug(
                "all conditions passed; swapping sourceSlot={} -> maceSlot={}; target={}",
                sourceSlot,
                foundMaceSlot,
                describeEntity(target)
        );
        actionbar(client, "PASS: swap " + sourceSlot + " -> " + foundMaceSlot);
        selectSlot(client, inventory, foundMaceSlot, "swap to mace");
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

        debug("end tick while swap active: restoreDelayTicks={}", restoreDelayTicks);

        if (restoreDelayTicks > 0) {
            restoreDelayTicks--;
            return;
        }

        restoreSource(client, "restore delay elapsed");
    }

    private static void restoreSource(MinecraftClient client, String reason) {
        if (!attackSwapActive) {
            return;
        }

        if (client == null
                || client.player == null
                || client.getNetworkHandler() == null) {
            debug("cannot restore source; clearing state; reason={}", reason);
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

        debug(
                "restore requested: reason={}, slot={}, canRestore={}, currentSlot={}",
                reason,
                slotToRestore,
                canRestore,
                inventory.selectedSlot
        );
        clearState();

        if (canRestore && inventory.selectedSlot != slotToRestore) {
            selectSlot(client, inventory, slotToRestore, "restore source item");
            actionbar(client, "RESTORE: slot " + slotToRestore);
        }
    }

    private static int findNamedMaceSlot(PlayerInventory inventory) {
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            debug("hotbar scan slot={}: {}", slot, describeStack(stack));
            if (matches(stack, Items.MACE, MACE_ITEM_NAME)) {
                debug("named mace matched at slot={}", slot);
                return slot;
            }
        }
        return PlayerInventory.NOT_FOUND;
    }

    private static boolean matches(ItemStack stack, Item item, String exactName) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        String actualName = stack.getName().getString().trim();
        boolean itemMatches = stack.isOf(item);
        boolean nameMatches = exactName.equals(actualName);

        debug(
                "item match check: expectedItem={}, actualItem={}, itemMatches={}, expectedName='{}', actualName='{}', actualNameCodePoints={}, nameMatches={}",
                Registries.ITEM.getId(item),
                Registries.ITEM.getId(stack.getItem()),
                itemMatches,
                exactName,
                actualName,
                codePoints(actualName),
                nameMatches
        );

        return itemMatches && nameMatches;
    }

    private static void selectSlot(
            MinecraftClient client,
            PlayerInventory inventory,
            int slot,
            String reason
    ) {
        int previousSlot = inventory.selectedSlot;
        inventory.setSelectedSlot(slot);
        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
        debug("slot packet sent: {} -> {}, reason={}", previousSlot, slot, reason);
    }

    private static void dumpStatus(MinecraftClient client, boolean sendToChat) {
        String[] lines = buildStatusLines(client);
        for (String line : lines) {
            debug("STATUS {}", line);
            if (sendToChat) {
                sendChat(client, line);
            }
        }
    }

    private static String[] buildStatusLines(MinecraftClient client) {
        if (client == null) {
            return new String[]{"client=null, initialized=" + initialized + ", debugEnabled=" + debugEnabled};
        }
        if (client.player == null) {
            return new String[]{
                    "initialized=" + initialized + ", debugEnabled=" + debugEnabled,
                    "player=null, network=" + (client.getNetworkHandler() != null),
                    "crosshair=" + describeCrosshair(client)
            };
        }

        ClientPlayerEntity player = client.player;
        PlayerInventory inventory = player.getInventory();
        int selectedSlot = inventory.selectedSlot;
        ItemStack heldStack = PlayerInventory.isValidHotbarIndex(selectedSlot)
                ? inventory.getStack(selectedSlot)
                : ItemStack.EMPTY;
        int maceSlot = findNamedMaceSlot(inventory);

        return new String[]{
                "initialized=" + initialized + ", debugEnabled=" + debugEnabled
                        + ", swapActive=" + attackSwapActive + ", restoreDelayTicks=" + restoreDelayTicks,
                "screen=" + (client.currentScreen == null
                        ? "null"
                        : client.currentScreen.getClass().getSimpleName())
                        + ", network=" + (client.getNetworkHandler() != null)
                        + ", spectator=" + player.isSpectator(),
                "selectedSlot=" + selectedSlot + ", held=" + describeStack(heldStack),
                String.format(
                        Locale.ROOT,
                        "movement: onGround=%s, velocityY=%.4f, fallDistance=%.3f, smashReady=%s",
                        player.isOnGround(),
                        player.getVelocity().y,
                        player.fallDistance,
                        isSmashReady(player)
                ),
                "crosshair=" + describeCrosshair(client),
                "foundMaceSlot=" + maceSlot + ", hotbar=" + describeHotbar(inventory)
        };
    }

    private static String describeCrosshair(MinecraftClient client) {
        if (client == null || client.crosshairTarget == null) {
            return "null";
        }
        if (client.crosshairTarget instanceof EntityHitResult entityHitResult) {
            return "ENTITY/" + describeEntity(entityHitResult.getEntity());
        }
        return client.crosshairTarget.getType()
                + "/"
                + client.crosshairTarget.getClass().getSimpleName();
    }

    private static String describeEntity(Entity entity) {
        if (entity == null) {
            return "null";
        }
        return Registries.ENTITY_TYPE.getId(entity.getType())
                + "(" + entity.getClass().getSimpleName() + ")";
    }

    private static String describeStack(ItemStack stack) {
        if (stack == null) {
            return "null";
        }
        if (stack.isEmpty()) {
            return "empty";
        }

        String name = stack.getName().getString();
        return "item=" + Registries.ITEM.getId(stack.getItem())
                + ", name='" + name + "'"
                + ", trimmed='" + name.trim() + "'"
                + ", codePoints=" + codePoints(name.trim())
                + ", count=" + stack.getCount();
    }

    private static String describeHotbar(PlayerInventory inventory) {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (int slot = 0; slot < PlayerInventory.getHotbarSize(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                joiner.add(slot + ":{" + describeStack(stack) + "}");
            }
        }
        return joiner.toString();
    }

    private static String codePoints(String value) {
        if (value == null || value.isEmpty()) {
            return "[]";
        }

        StringJoiner joiner = new StringJoiner(" ", "[", "]");
        value.codePoints().forEach(codePoint ->
                joiner.add(String.format(Locale.ROOT, "U+%04X", codePoint))
        );
        return joiner.toString();
    }

    private static void block(MinecraftClient client, String reason) {
        debug("BLOCK: {}", reason);
        actionbar(client, "BLOCK: " + reason);
    }

    private static void actionbar(MinecraftClient client, String message) {
        if (!debugEnabled || client == null || client.player == null) {
            return;
        }
        client.player.sendMessage(Text.literal(LOG_PREFIX + message), true);
    }

    private static void sendChat(MinecraftClient client, String message) {
        if (client != null && client.player != null) {
            client.player.sendMessage(Text.literal(LOG_PREFIX + message), false);
        }
    }

    private static void debug(String message, Object... arguments) {
        if (debugEnabled) {
            LOGGER.info(LOG_PREFIX + message, arguments);
        }
    }

    private static void clearState() {
        attackSwapActive = false;
        sourceSlot = PlayerInventory.NOT_FOUND;
        restoreDelayTicks = 0;
    }
}
