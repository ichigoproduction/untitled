package untitled.untitled.client.mixin;

import untitled.untitled.client.KillEffect;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class KillEffectAttackMixin {
    @Inject(method = "attackEntity", at = @At("HEAD"))
    private void untitled$trackKillEffect(PlayerEntity player, Entity target, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player == player) KillEffect.onLocalAttack(target);
    }
}
