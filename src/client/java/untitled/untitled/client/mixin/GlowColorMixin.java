package untitled.untitled.client.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import untitled.untitled.client.Glow;

/** Return RGB for the entity being outlined, not a global last-rendered color. */
@Mixin(Entity.class)
public abstract class GlowColorMixin {
    @Inject(method = "getTeamColorValue()I", at = @At("HEAD"), cancellable = true)
    private void untitled$glowColor(CallbackInfoReturnable<Integer> cir) {
        Integer override = Glow.colorFor((Entity) (Object) this);
        if (override != null) cir.setReturnValue(override);
    }
}
