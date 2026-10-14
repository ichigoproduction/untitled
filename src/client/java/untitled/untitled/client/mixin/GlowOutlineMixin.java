package untitled.untitled.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import untitled.untitled.client.Glow;

/** Enable custom outlines without disabling vanilla/server glowing. */
@Mixin(MinecraftClient.class)
public abstract class GlowOutlineMixin {
    @Inject(method = "hasOutline(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void untitled$glowOutline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (Glow.shouldOutline(entity)) cir.setReturnValue(true);
    }
}
