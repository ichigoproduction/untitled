package untitled.untitled.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import untitled.untitled.client.SkillHud;

/**
 * Capture every vanilla server actionbar packet, including rapid messages
 * that another mod could modify or replace before final HUD display.
 * The client executor handles packets on the main game thread.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class SkillHudPacketMixin {
    @Inject(method = "onOverlayMessage(Lnet/minecraft/network/packet/s2c/play/OverlayMessageS2CPacket;)V",
            at = @At("HEAD"))
    private void untitled$skillOverlayPacket(OverlayMessageS2CPacket packet, CallbackInfo ci) {
        MinecraftClient.getInstance().execute(() -> SkillHud.onHudText(packet.text()));
    }

    @Inject(method = "onGameMessage(Lnet/minecraft/network/packet/s2c/play/GameMessageS2CPacket;)V",
            at = @At("HEAD"))
    private void untitled$skillGamePacket(GameMessageS2CPacket packet, CallbackInfo ci) {
        if (packet.overlay()) {
            MinecraftClient.getInstance().execute(() -> SkillHud.onHudText(packet.content()));
        }
    }
}
