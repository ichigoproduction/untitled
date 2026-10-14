package untitled.untitled.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.TitleHud;

/**
 * Replaces only the Minecraft 1.21.4 title/subtitle draw call.
 * Vanilla tick lifecycle and incoming title packets are unaffected.
 */
@Mixin(InGameHud.class)
public abstract class TitleHudRenderMixin {
    @Shadow private Text title;
    @Shadow private Text subtitle;
    @Shadow private int titleRemainTicks;
    @Shadow private int titleFadeInTicks;
    @Shadow private int titleStayTicks;
    @Shadow private int titleFadeOutTicks;

    @Inject(method = "renderTitleAndSubtitle(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"), cancellable = true)
    private void untitled$renderCustomTitle(DrawContext context,
                                             RenderTickCounter tickCounter,
                                             CallbackInfo ci) {
        ci.cancel();
        if (titleRemainTicks <= 0 || (title == null && subtitle == null)) return;

        float remaining = titleRemainTicks - tickCounter.getTickDelta(false);
        int alpha = 255;
        if (titleRemainTicks > titleFadeOutTicks + titleStayTicks && titleFadeInTicks > 0) {
            float elapsed = titleFadeInTicks + titleStayTicks + titleFadeOutTicks - remaining;
            alpha = (int) (elapsed * 255.0F / titleFadeInTicks);
        }
        if (titleRemainTicks <= titleFadeOutTicks && titleFadeOutTicks > 0) {
            alpha = (int) (remaining * 255.0F / titleFadeOutTicks);
        }
        alpha = Math.max(0, Math.min(255, alpha));
        if (alpha > 0) TitleHud.render(context, title, subtitle, alpha);
    }
}
