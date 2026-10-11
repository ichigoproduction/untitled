package untitled.untitled.client.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.SkillHud;

/** Forwards the rendered actionbar text to the skill HUD. */
@Mixin(InGameHud.class)
public abstract class SkillHudMessageMixin {
    @Inject(method = "setOverlayMessage(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"))
    private void untitled$skillOverlay(Text message, boolean tinted, CallbackInfo ci) {
        SkillHud.onHudText(message);
    }
}
