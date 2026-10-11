package untitled.untitled.client.mixin;

import net.minecraft.client.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.SkillHud;

/** Records crouch double-taps without consuming vanilla key presses. */
@Mixin(Keyboard.class)
public abstract class SkillHudKeyboardMixin {
    @Inject(method = "onKey(JIIII)V", at = @At("HEAD"))
    private void untitled$trackDoubleSneak(long window, int key, int scancode,
                                            int action, int modifiers, CallbackInfo ci) {
        SkillHud.onKeyInput(window, key, scancode, action);
    }
}
