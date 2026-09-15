package untitled.untitled.client.mixin;

import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import untitled.untitled.client.ItemModelOverrides;

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @ModifyArgs(
            method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ModelTransformationMode;IILnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/world/World;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/item/ItemModelManager;update(Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ModelTransformationMode;Lnet/minecraft/world/World;Lnet/minecraft/entity/LivingEntity;I)V"
            )
    )
    private void untitled$replaceGuiModel(Args args) {
        ItemStack original = args.get(1);
        ModelTransformationMode mode = args.get(2);
        args.set(1, ItemModelOverrides.resolveGuiStack(original, mode));
    }
}
