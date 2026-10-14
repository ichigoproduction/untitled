package untitled.untitled.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import untitled.untitled.client.SkinChanger;

/** Only replace our local player's skin/cape/elytra textures. */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class SkinChangerMixin {
    @Inject(method = "getSkinTextures()Lnet/minecraft/client/util/SkinTextures;",
            at = @At("RETURN"), cancellable = true)
    private void untitled$overrideSelfCosmetics(CallbackInfoReturnable<SkinTextures> cir) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return;
        AbstractClientPlayerEntity player = (AbstractClientPlayerEntity) (Object) this;
        if (!player.getUuid().equals(client.player.getUuid())) return;

        SkinTextures base = cir.getReturnValue();
        if (base == null) return;
        SkinTextures skin = SkinChanger.getOverrideSkinTextures();
        SkinTextures result = skin != null ? skin : base;

        Identifier cape = SkinChanger.getOverrideCapeTexture();
        Identifier elytra = SkinChanger.getOverrideElytraTexture();
        if (cape != null || elytra != null) {
            result = new SkinTextures(result.texture(), result.textureUrl(),
                    cape != null ? cape : result.capeTexture(),
                    elytra != null ? elytra : result.elytraTexture(),
                    result.model(), result.secure());
        }
        if (result != base) cir.setReturnValue(result);
    }
}
