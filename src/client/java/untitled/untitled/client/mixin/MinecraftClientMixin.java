package untitled.untitled.client.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import untitled.untitled.client.ItemModelRuntime;
import untitled.untitled.client.SkillHud;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "doAttack()Z", at = @At("HEAD"))
    private void untitled$trackModelAttack(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        ItemModelRuntime.onAttack(client);
        SkillHud.onAttackInput(client);
    }

    @Inject(method = "doItemUse()V", at = @At("HEAD"))
    private void untitled$trackSkillUse(CallbackInfo ci) {
        SkillHud.onUseInput((MinecraftClient) (Object) this);
    }
}
