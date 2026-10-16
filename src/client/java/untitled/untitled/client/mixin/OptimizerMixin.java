package untitled.untitled.client.mixin;

import untitled.untitled.client.Optimizer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
public final class OptimizerMixin {
    private OptimizerMixin() {}

    // ============================================================
    // 1) DisableShadows
    // ============================================================
    @Mixin(EntityRenderDispatcher.class)
    public static class DisableShadowsMixin {
        @Inject(method = "configure", at = @At("TAIL"))
        private void untitled$configureShadows(World world, Camera camera, Entity target, CallbackInfo ci) {
            ((EntityRenderDispatcher) (Object) this).setRenderShadows(!Optimizer.disableShadows());
        }
    }

    // ============================================================
    // 2) NoBreakParticles
    // ============================================================
    @Mixin(ParticleManager.class)
    public static class NoBreakParticlesMixin {
        @Inject(
                method = "addBlockBreakingParticles(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)V",
                at = @At("HEAD"),
                cancellable = true
        )
        private void untitled$noBlockBreaking(BlockPos pos, Direction direction, CallbackInfo ci) {
            if (Optimizer.disableBreakParticles()) {
                ci.cancel();
            }
        }

        @Inject(
                method = "addBlockBreakParticles(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V",
                at = @At("HEAD"),
                cancellable = true
        )
        private void untitled$noBlockBreak(BlockPos pos, BlockState state, CallbackInfo ci) {
            if (Optimizer.disableBreakParticles()) {
                ci.cancel();
            }
        }
    }

    // ============================================================
    // 3) NoWeatherParticles
    // ============================================================
    @Mixin(WorldRenderer.class)
    public static class NoWeatherParticlesMixin {
        @Inject(method = "renderWeather", at = @At("HEAD"), cancellable = true)
        private void untitled$cancelWeatherRender(CallbackInfo ci) {
            if (Optimizer.noWeatherParticles()) {
                ci.cancel();
            }
        }
    }

    // ============================================================
    // 4) Fog / Blindness / Darkness fog
    // ============================================================
    @Mixin(BackgroundRenderer.class)
    public static class FogVisionMixin {

        @Redirect(
                method = "applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;Lorg/joml/Vector4f;FZF)Lnet/minecraft/client/render/Fog;",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/entity/LivingEntity;hasStatusEffect(Lnet/minecraft/registry/entry/RegistryEntry;)Z"
                ),
                require = 0
        )
        private static boolean untitled$ignoreBlindnessDarknessForFog(LivingEntity living, RegistryEntry<?> effect) {
            if (effect == StatusEffects.BLINDNESS && Optimizer.removeBlindnessFog()) {
                return false;
            }
            if (effect == StatusEffects.DARKNESS && Optimizer.removeDarknessFog()) {
                return false;
            }
            return living.hasStatusEffect((RegistryEntry) effect);
        }

        @Inject(
                method = "applyFog(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/BackgroundRenderer$FogType;Lorg/joml/Vector4f;FZF)Lnet/minecraft/client/render/Fog;",
                at = @At("TAIL"),
                cancellable = true
        )
        private static void untitled$adjustFog(
                Camera camera,
                BackgroundRenderer.FogType fogType,
                Vector4f color,
                float viewDistance,
                boolean thickenFog,
                float tickProgress,
                CallbackInfoReturnable<Fog> cir
        ) {
            Fog original = cir.getReturnValue();
            if (original == null) return;

            boolean shouldAdjust = false;
            float newEnd = original.end();

            if (Optimizer.disableFog() && fogType == BackgroundRenderer.FogType.FOG_TERRAIN) {
                shouldAdjust = true;
                newEnd = Math.max(newEnd, viewDistance * 4.0f);
            }

            var submersion = camera.getSubmersionType();

            if (Optimizer.softenWaterOverlay() && "WATER".equals(submersion.name())) {
                shouldAdjust = true;
                newEnd = Math.max(newEnd, viewDistance * 4.0f);
            }

            if (Optimizer.softenLavaVision() && "LAVA".equals(submersion.name())) {
                shouldAdjust = true;
                newEnd = Math.max(newEnd, viewDistance * 4.0f);
            }

            if (!shouldAdjust) return;

            Fog fog = new Fog(
                    0.0f,
                    newEnd,
                    original.shape(),
                    original.red(),
                    original.green(),
                    original.blue(),
                    original.alpha()
            );
            cir.setReturnValue(fog);
        }
    }

    // ============================================================
    // 5) Darkness Pulse 제거 (1.21.4 안전판)
    //    핵심: ClientPlayerEntity가 아니라
    //    LightmapTextureManager#getDarknessFactor(float)를 직접 막는다.
    // ============================================================
    @Mixin(LightmapTextureManager.class)
    public static class DarknessPulseMixin {
        @Inject(method = "getDarknessFactor(F)F", at = @At("HEAD"), cancellable = true)
        private void untitled$removeDarknessPulse(float delta, CallbackInfoReturnable<Float> cir) {
            if (Optimizer.removeDarknessPulse()) {
                cir.setReturnValue(0.0F);
            }
        }
    }

    // ============================================================
    // 6) AntiNauseaWobble
    // ============================================================
    @Mixin(GameRenderer.class)
    public static class AntiNauseaWobbleMixin {
        @Redirect(
                method = "renderWorld",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/util/math/MathHelper;lerp(FFF)F",
                        ordinal = 0
                ),
                require = 0
        )
        private float untitled$disableNauseaWobble(float delta, float start, float end) {
            if (!Optimizer.antiNauseaWobble()) {
                return MathHelper.lerp(delta, start, end);
            }
            return 0.0F;
        }
    }

    // ============================================================
    // 7) Overlay 처리 + NoVignette
    //    핵심: 1.21.4에서 vignette는 InGameHud에 있다.
    // ============================================================
    @Mixin(InGameHud.class)
    public static abstract class InGameHudOverlayMixin {
        @Shadow
        protected abstract void renderOverlay(DrawContext context, Identifier texture, float opacity);

        @Inject(method = "renderVignetteOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/entity/Entity;)V",
                at = @At("HEAD"),
                cancellable = true)
        private void untitled$cancelVignette(DrawContext context, Entity entity, CallbackInfo ci) {
            if (Optimizer.noVignette()) {
                ci.cancel();
            }
        }

        @Redirect(
                method = "renderMiscOverlays(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/client/gui/hud/InGameHud;renderOverlay(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;F)V"
                ),
                require = 0
        )
        private void untitled$handleOverlays(InGameHud instance, DrawContext context, Identifier texture, float opacity) {
            String path = texture.getPath();
            if (path == null) {
                this.renderOverlay(context, texture, opacity);
                return;
            }

            if (Optimizer.antiPumpkinOverlay() && path.contains("pumpkin")) {
                return;
            }

            if (Optimizer.antiPortalOverlay() && path.contains("portal")) {
                this.renderOverlay(context, texture, Optimizer.getPortalOverlayAlpha());
                return;
            }

            if (Optimizer.softenPowderSnowOverlay() && path.contains("powder_snow")) {
                this.renderOverlay(context, texture, Optimizer.getPowderSnowOverlayAlpha());
                return;
            }

            this.renderOverlay(context, texture, opacity);
        }
    }

    // ============================================================
    // 8) Fire / Water Overlay
    // ============================================================
    @Mixin(value = InGameOverlayRenderer.class, priority = 50)
    public static abstract class FireWaterOverlayMixin {
        @ModifyConstant(
                method = "renderFireOverlay",
                constant = @Constant(floatValue = 0.9F),
                require = 0
        )
        private static float untitled$softenFireOverlayAlpha(float original) {
            if (!Optimizer.antiFireOverlay()) {
                return original;
            }
            return 0.35F;
        }

        @ModifyConstant(
                method = "renderFireOverlay",
                constant = @Constant(floatValue = -0.3F),
                require = 0
        )
        private static float untitled$lowerFireOverlay(float original) {
            if (!Optimizer.antiFireOverlay()) {
                return original;
            }
            return original * Optimizer.getFireOverlayYMultiplier();
        }

        @ModifyConstant(
                method = "renderUnderwaterOverlay",
                constant = @Constant(floatValue = 0.1F),
                require = 0
        )
        private static float untitled$softenWaterOverlayAlpha(float original) {
            if (!Optimizer.softenWaterOverlay()) {
                return original;
            }
            return Optimizer.getWaterOverlayAlpha();
        }
    }

    // ============================================================
    // 9) PortalMenu
    // ============================================================
    @Mixin(ClientPlayerEntity.class)
    public static abstract class PortalMenuMixin {
        @Redirect(
                method = "tickNausea",
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/client/gui/screen/Screen;shouldPause()Z"
                ),
                require = 0
        )
        private boolean untitled$portalMenuKeepScreen(Screen screen) {
            if (!Optimizer.portalMenu()) {
                return screen.shouldPause();
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.currentScreen != null) {
                return true;
            }
            return screen.shouldPause();
        }
    }

    // ============================================================
    // 10) TransparentLoadingTerrain
    // ============================================================
    @Mixin(DownloadingTerrainScreen.class)
    public static abstract class LoadingTerrainScreenMixin {
        @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
        private void untitled$transparentLoadingBackground(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
            if (Optimizer.transparentLoadingTerrain()) {
                ci.cancel();
            }
        }
    }
}