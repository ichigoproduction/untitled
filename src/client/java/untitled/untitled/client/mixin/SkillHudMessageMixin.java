package untitled.untitled.client.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.SkillHud;

/**
 * Observe the HUD entry points rather than only Fabric's GAME message event.
 * The dedicated OverlayMessageS2CPacket bypasses GAME but uses setOverlayMessage.
 * Title/subtitle hooks are diagnostic only.
 */
@Mixin(InGameHud.class)
public abstract class SkillHudMessageMixin {
    @Inject(method = "setOverlayMessage(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"))
    private void untitled$skillOverlay(Text message, boolean tinted, CallbackInfo ci) {
        SkillHud.onHudText(message, "ACTIONBAR");
    }

    @Inject(method = "setTitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void untitled$skillTitle(Text message, CallbackInfo ci) {
        SkillHud.onHudText(message, "TITLE");
    }

    @Inject(method = "setSubtitle(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void untitled$skillSubtitle(Text message, CallbackInfo ci) {
        SkillHud.onHudText(message, "SUBTITLE");
    }
}
