package untitled.untitled.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class NewSwap {
    private static final String SOURCE_ITEM_NAME = "연";
    private static final String MACE_ITEM_NAME = "슈레더";
    private static final double MIN_LOCAL_DROP_DISTANCE = 1.5D;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("untitled_newswap.json");

    private static boolean initialized = false;
    private static boolean enabled = true;
    private static boolean attackSwapActive = false;
    private static int sourceSlot = PlayerInventory.NOT_FOUND;
    private static int restoreDelayTicks = 0;

    private static ClientPlayerEntity heightTrackedPlayer = null;
    private static double highestAirY = Double.NaN;
    private static double localDropDistance = 0.0D;

    private NewSwap() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        loadSettings();
        ClientTickEvents.END_CLIENT_TICK.register(NewSwap::onEndClientTick);
        registerCommands();
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("newswap")
                        .then(literal("toggle").executes(context -> {
                            MinecraftClient client = MinecraftClient.getInstance();
                            enabled = !enabled;

                            if (!enabled) {
                                restoreSource(client);
                                resetHeightTracking();
                            } else {
                                updateHeightTracking(client);
                            }

                            saveSettings();
                            if (client.player != null) {
                                client.player.sendMessage(
                                        Text.literal("NewSwap: " + (enabled ? "ON" : "OFF")),
                                        false
                                );
                            }
                            return 1;
                        })))
        );
    }

    public static void onLeftMousePress(MinecraftClient client) {
        if (!enabled || client == null) {
            return;
        }

        updateHeightTracking(client);
        restoreSource(client);

        if (!initialized
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

        int maceSlot = findNamedMaceSlot(inventory);
        if (!PlayerInventory.isValidHotbarIndex(maceSlot)
                || maceSlot == selectedSlot) {
            return;
        }

        sourceSlot = selectedSlot;
        attackSwapActive = true;
        restoreDelayTicks = 1;
        selectSlot(client, inventory, maceSlot);
    }

    private static boolean isSmashReady(ClientPlayerEntity player) {
        return player != null
                && !player.isOnGround()
                && player.getVelocity().y < 0.0D
                && localDropDistance > MIN_LOCAL_DROP_DISTANCE;
    }

    private static void updateHeightTracking(MinecraftClient client) {
        if (client == null || client.player == null) {
            resetHeightTracking();
            return;
        }

        ClientPlayerEntity player = client.player;
        double currentY = player.getY();

        if (heightTrackedPlayer != player) {
            heightTrackedPlayer = player;
            highestAirY = currentY;
            localDropDistance = 0.0D;
            return;
        }

        if (player.isOnGround()) {
            highestAirY = currentY;
            localDropDistance = 0.0D;
            return;
        }

        if (Double.isNaN(highestAirY) || currentY > highestAirY) {
            highestAirY = currentY;
        }

        localDropDistance = Math.max(0.0D, highestAirY - currentY);
    }

    private static void resetHeightTracking() {
        heightTrackedPlayer = null;
        highestAirY = Double.NaN;
        localDropDistance = 0.0D;
    }

    private static void onEndClientTick(MinecraftClient client) {
        if (!enabled) {
            restoreSource(client);
            resetHeightTracking();
            return;
        }

        updateHeightTracking(client);

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
            clearSwapState();
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

        clearSwapState();

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

    private static void loadSettings() {
        enabled = true;

        if (!Files.isRegularFile(CONFIG_PATH)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root != null && root.has("enabled")) {
                enabled = root.get("enabled").getAsBoolean();
            }
        } catch (Exception ignored) {
            enabled = true;
        }
    }

    private static void saveSettings() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);

            try (Writer writer = Files.newBufferedWriter(
                    CONFIG_PATH,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                GSON.toJson(root, writer);
            }
        } catch (Exception ignored) {
        }
    }

    private static void clearSwapState() {
        attackSwapActive = false;
        sourceSlot = PlayerInventory.NOT_FOUND;
        restoreDelayTicks = 0;
    }
}
