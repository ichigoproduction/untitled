package untitled.untitled.client.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import untitled.untitled.client.GhostItemPreview;

/** Feed the first-person render method a ghost stack only if the real hand is empty. */
@Mixin(HeldItemRenderer.class)
public abstract class GhostFirstPersonMixin {
    @ModifyVariable(method = "renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At("HEAD"), argsOnly = true, index = 6)
    private ItemStack untitled$firstPersonGhost(ItemStack actual,
                                               AbstractClientPlayerEntity player, float tickDelta,
                                               float pitch, Hand hand, float swingProgress, ItemStack item,
                                               float equipProgress,
                                               net.minecraft.client.util.math.MatrixStack matrices,
                                               net.minecraft.client.render.VertexConsumerProvider vertexConsumers,
                                               int light) {
        return GhostItemPreview.forFirstPerson(hand, actual, player);
    }
}
