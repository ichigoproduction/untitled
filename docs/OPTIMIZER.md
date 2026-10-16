# Optimizer — Minecraft 1.21.4 Fabric client

Ported from [ichigoproduction/152](https://github.com/ichigoproduction/152) `Optimizer.java` and `OptimizerMixin.java`, using the `untitled` client command rather than the 152 UI module. The original weather-information HUD and blindness-related sprint bypass from Churrrrr are **not** included.

## Command
- `/optimizer toggle`: simultaneously enable or disable all Optimizer features.
- OFF by default on a fresh installation; saved in `config/152_optimizer.json` as `{"enabled":true|false}`, restored on next launch.
- Chat feedback: `optimizer → ON` or `optimizer → OFF`.

## Effects covered by the single toggle
- Remove entity shadows
- Prevent block-breaking particle effects
- Skip weather particle rendering (not the Churrrrr weather-information HUD)
- Push terrain fog farther away; soften water and lava fog
- Ignore blindness/darkness status-effect checks in the fog path
- Remove the darkness brightness pulse
- Suppress nausea camera wobble
- Remove pumpkin vision obstruction and vignette
- Reduce portal overlay opacity (0.20)
- Reduce fire overlay opacity (0.35) and lower its screen position
- Remove underwater overlay (0.0 alpha)
- Reduce powder-snow overlay opacity (0.20)
- Keep GUI open in nether portal
- Make the loading terrain background transparent

## Scope and compatibility
- Client-side visual changes only. No server packets, player speed modification, or Churrrrr sprint bypass.
- All 10 optimizer mixin classes remain installed, but check the one toggle before changing rendering.
- Uses the 152 injection targets for Minecraft 1.21.4 / Yarn build.8. Several optional injection sites use `require = 0`; if a target method is modified by another mod, its feature might not apply even though Optimizer is ON.
- Warning: these effects include game-vision changes and are not a measured FPS boost. Shader packs, Lunar or overlapping visual mixins can conflict; verify in game.
- Run `./gradlew build` (Windows: `.\gradlew.bat build`) and check the relevant visuals while toggling.
