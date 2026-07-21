package untitled.untitled.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.NewSwap;

@Mixin(Mouse.class)
public abstract class NewSwapMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void untitled$beforeMouseButton(
            long window,
            int button,
            int action,
            int mods,
            CallbackInfo info
    ) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT
                || action != GLFW.GLFW_PRESS) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getWindow().getHandle() != window) {
            return;
        }

        NewSwap.onLeftMousePress(client);
    }
}
