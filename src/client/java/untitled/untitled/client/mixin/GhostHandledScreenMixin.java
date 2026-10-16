package untitled.untitled.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import untitled.untitled.client.GhostItemPreview;

/** Draw ghost stacks in vanilla player slots and cancel only virtual click operations. */
@Mixin(HandledScreen.class)
public abstract class GhostHandledScreenMixin {
    @Shadow protected Slot focusedSlot;

    @Inject(method = "drawSlot(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/screen/slot/Slot;)V",
            at = @At("TAIL"))
    private void untitled$drawGhostSlot(DrawContext context, Slot slot, CallbackInfo ci) {
        ItemStack ghost = GhostItemPreview.forSlot(slot);
        if (ghost.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        context.drawItem(ghost, slot.x, slot.y);
        context.drawStackOverlay(client.textRenderer, ghost, slot.x, slot.y);
    }

    @Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
            at = @At("HEAD"), cancellable = true)
    private void untitled$clickGhost(Slot slot, int slotId, int button,
                                   SlotActionType action, CallbackInfo ci) {
        if (GhostItemPreview.click(slot, button, action)) ci.cancel();
    }

    @Inject(method = "drawMouseoverTooltip(Lnet/minecraft/client/gui/DrawContext;II)V",
            at = @At("HEAD"), cancellable = true)
    private void untitled$ghostTooltip(DrawContext context, int mouseX, int mouseY, CallbackInfo ci) {
        if (GhostItemPreview.hasCursor() || focusedSlot == null) return;
        ItemStack ghost = GhostItemPreview.forSlot(focusedSlot);
        if (ghost.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        context.drawItemTooltip(client.textRenderer, ghost, mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"))
    private void untitled$drawGhostCursor(DrawContext context, int mouseX, int mouseY,
                                         float delta, CallbackInfo ci) {
        ItemStack cursor = GhostItemPreview.cursorStack();
        if (cursor.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        context.drawItem(cursor, mouseX - 8, mouseY - 8);
        context.drawStackOverlay(client.textRenderer, cursor, mouseX - 8, mouseY - 8);
    }

    @Inject(method = "mouseDragged(DDIDD)Z", at = @At("HEAD"), cancellable = true)
    private void untitled$blockVirtualDrag(double mouseX, double mouseY, int button,
                                          double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
        if (GhostItemPreview.hasCursor()) cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased(DDI)Z", at = @At("HEAD"), cancellable = true)
    private void untitled$blockVirtualRelease(double mouseX, double mouseY, int button,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (GhostItemPreview.hasCursor()) cir.setReturnValue(true);
    }
}
