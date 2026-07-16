package untitled.untitled.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import untitled.untitled.client.CameraControl;

@Environment(EnvType.CLIENT)
@Mixin(Camera.class)
public abstract class CameraMixin {
    @ModifyVariable(method = "clipToSpace", at = @At("HEAD"), argsOnly = true)
    private float untitled$overrideCameraDistance(float desiredCameraDistance) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options.getPerspective().isFirstPerson()) {
            return desiredCameraDistance;
        }

        float configuredDistance = CameraControl.getCameraDistance();
        return configuredDistance <= 0.01F
                ? desiredCameraDistance
                : configuredDistance;
    }

    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void untitled$applyCameraNoclip(
            float desiredCameraDistance,
            CallbackInfoReturnable<Float> callback
    ) {
        if (!CameraControl.isCameraNoclipEnabled()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options.getPerspective().isFirstPerson()) {
            return;
        }

        float configuredDistance = CameraControl.getCameraDistance();
        callback.setReturnValue(configuredDistance <= 0.01F
                ? desiredCameraDistance
                : configuredDistance);
    }
}
