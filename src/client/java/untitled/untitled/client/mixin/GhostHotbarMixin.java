package untitled.untitled.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.GhostItemPreview;

/** Render nine virtual icons after vanilla hotbar, without relying on item render seeds. */
@Mixin(InGameHud.class)
public abstract class GhostHotbarMixin {
    @Inject(method = "renderHotbar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("TAIL"))
    private void untitled$renderGhostHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.player.isSpectator()) return;

        int x0 = context.getScaledWindowWidth() / 2 - 88;
        int y = context.getScaledWindowHeight() - 19;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack ghost = GhostItemPreview.forHotbar(slot, ItemStack.EMPTY);
            if (ghost.isEmpty()) continue;
            int x = x0 + slot * 20;
            context.drawItem(ghost, x, y);
            context.drawStackOverlay(client.textRenderer, ghost, x, y);
        }
    }
}
