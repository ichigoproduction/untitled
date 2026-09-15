package untitled.untitled.client;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class ItemModelInspector {
    private static final int CHAT_VALUE_CHUNK = 220;
    private static boolean initialized = false;

    private ItemModelInspector() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(
                        literal("imodelinspect")
                                .executes(context -> inspectHeldItem(context.getSource()))
                )
        );
    }

    private static int inspectHeldItem(FabricClientCommandSource source) {
        ItemStack held = source.getPlayer().getMainHandStack();
        if (held.isEmpty()) {
            held = source.getPlayer().getOffHandStack();
        }
        if (held.isEmpty()) {
            source.sendError(Text.literal("검사할 아이템을 손에 들어주세요."));
            return 0;
        }

        try {
            ItemStack snapshot = held.copyWithCount(1);
            Identifier itemId = Registries.ITEM.getId(snapshot.getItem());
            NbtElement encoded = snapshot.toNbt(source.getWorld().getRegistryManager());
            if (!(encoded instanceof NbtCompound stackNbt)) {
                source.sendError(Text.literal("아이템 NBT 형식이 예상과 다릅니다."));
                return 0;
            }
            NbtCompound components = stackNbt.getCompound("components");

            source.sendFeedback(Text.literal(
                    "모델 검사: " + snapshot.getName().getString() + " (" + itemId + ")"
            ));
            source.sendFeedback(Text.literal(
                    "components: " + components.getSize() + "개"
            ));

            if (components.isEmpty()) {
                source.sendFeedback(Text.literal("- components 없음"));
                return 1;
            }

            List<String> keys = new ArrayList<>(components.getKeys());
            Collections.sort(keys);
            for (String key : keys) {
                NbtElement element = components.get(key);
                String value = element == null ? "null" : element.toString();
                sendChunkedComponent(source, key, value);
            }
            return components.getSize();
        } catch (Exception exception) {
            source.sendError(Text.literal("아이템 component를 읽지 못했습니다."));
            return 0;
        }
    }

    private static void sendChunkedComponent(
            FabricClientCommandSource source,
            String key,
            String value
    ) {
        if (value.isEmpty()) {
            source.sendFeedback(Text.literal("- " + key + " = "));
            return;
        }

        int offset = 0;
        boolean firstLine = true;
        while (offset < value.length()) {
            int end = Math.min(offset + CHAT_VALUE_CHUNK, value.length());
            String chunk = value.substring(offset, end);
            source.sendFeedback(Text.literal(
                    firstLine ? "- " + key + " = " + chunk : "  " + chunk
            ));
            firstLine = false;
            offset = end;
        }
    }
}
