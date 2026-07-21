package untitled.untitled.mixin.client;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import untitled.untitled.client.NewSwap;

@Mixin(MinecraftClient.class)
public abstract class NewSwapMixin {
    @Inject(method = "doAttack", at = @At("HEAD"))
    private void untitled$beforeAttack(CallbackInfoReturnable<Boolean> info) {
        NewSwap.beforeAttack((MinecraftClient) (Object) this);
    }

    @Inject(method = "doAttack", at = @At("RETURN"))
    private void untitled$afterAttack(CallbackInfoReturnable<Boolean> info) {
        NewSwap.afterAttack((MinecraftClient) (Object) this);
    }
}
