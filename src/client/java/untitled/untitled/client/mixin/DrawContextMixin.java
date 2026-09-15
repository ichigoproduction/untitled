package untitled.untitled.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import untitled.untitled.client.ItemModelOverrides;

@Mixin(DrawContext.class)
public abstract class DrawContextMixin {
    @ModifyVariable(
            method = {
                    "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;III)V",
                    "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;Lnet/minecraft/item/ItemStack;IIII)V"
            },
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private ItemStack untitled$replaceGuiModel(ItemStack original) {
        return ItemModelOverrides.resolveGuiStack(original, ModelTransformationMode.GUI);
    }
}
