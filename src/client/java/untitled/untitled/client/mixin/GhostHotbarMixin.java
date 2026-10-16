package untitled.untitled.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.GhostItemPreview;

@Mixin(InGameHud.class)
public abstract class GhostHotbarMixin {
    @Inject(method = "renderHotbarItem(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/client/render/RenderTickCounter;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/item/ItemStack;I)V",
            at = @At("TAIL"))
    private void untitled$drawGhostHotbar(DrawContext context, int x, int y,
                                         RenderTickCounter tickCounter, PlayerEntity player,
                                         ItemStack actual, int seed, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != player || !actual.isEmpty()) return;
        int index = seed; // slot seed is assigned by vanilla hotbar as an inventory index
        ItemStack ghost = GhostItemPreview.forHotbar(index, actual);
        if (ghost.isEmpty()) return;
        context.drawItem(ghost, x, y);
        context.drawStackOverlay(client.textRenderer, ghost, x, y);
    }
}
