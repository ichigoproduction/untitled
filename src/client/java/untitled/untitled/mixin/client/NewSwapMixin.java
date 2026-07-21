package untitled.untitled.mixin.client;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.NewSwap;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class NewSwapMixin {
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void untitled$beforeAttack(
            PlayerEntity player,
            Entity target,
            CallbackInfo info
    ) {
        NewSwap.beforeAttack(player, target);
    }

    @Inject(method = "attackEntity", at = @At("RETURN"))
    private void untitled$afterAttack(
            PlayerEntity player,
            Entity target,
            CallbackInfo info
    ) {
        NewSwap.afterAttack(player);
    }
}
