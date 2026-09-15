package untitled.untitled.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ModelTransformationMode;
import net.minecraft.util.Arm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.ItemModelOverrides;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(
            method = "updateRenderState(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void untitled$replaceLocalPlayerModels(
            AbstractClientPlayerEntity player,
            PlayerEntityRenderState state,
            float tickDelta,
            CallbackInfo ci
    ) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || player != client.player) {
            return;
        }

        ItemModelManager itemModels = client.getItemModelManager();

        ItemStack right = ItemModelOverrides.resolveThirdPersonStack(
                player.getStackInArm(Arm.RIGHT),
                ModelTransformationMode.THIRD_PERSON_RIGHT_HAND,
                player
        );
        ItemStack left = ItemModelOverrides.resolveThirdPersonStack(
                player.getStackInArm(Arm.LEFT),
                ModelTransformationMode.THIRD_PERSON_LEFT_HAND,
                player
        );

        itemModels.updateForLivingEntity(
                state.rightHandItemState,
                right,
                ModelTransformationMode.THIRD_PERSON_RIGHT_HAND,
                false,
                player
        );
        itemModels.updateForLivingEntity(
                state.leftHandItemState,
                left,
                ModelTransformationMode.THIRD_PERSON_LEFT_HAND,
                true,
                player
        );

        ItemStack originalHead = player.getEquippedStack(EquipmentSlot.HEAD);
        ItemStack resolvedHead = ItemModelOverrides.resolveEquipmentStack(originalHead);
        state.equippedHeadStack = resolvedHead;
        state.equippedChestStack = ItemModelOverrides.resolveEquipmentStack(
                player.getEquippedStack(EquipmentSlot.CHEST)
        );
        state.equippedLegsStack = ItemModelOverrides.resolveEquipmentStack(
                player.getEquippedStack(EquipmentSlot.LEGS)
        );
        state.equippedFeetStack = ItemModelOverrides.resolveEquipmentStack(
                player.getEquippedStack(EquipmentSlot.FEET)
        );

        if (resolvedHead != originalHead) {
            itemModels.updateForLivingEntity(
                    state.headItemRenderState,
                    resolvedHead,
                    ModelTransformationMode.HEAD,
                    false,
                    player
            );
        }
    }
}
