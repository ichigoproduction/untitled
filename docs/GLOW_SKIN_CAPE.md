# Glow, Skin & Cape (Minecraft 1.21.4 Fabric client)

## Glow
- `/glow toggle` toggles the standalone Glow feature (self + manually registered UUIDs). Party team/enemy outlines are independent and remain controlled by `/party glow toggle`. Vanilla/server glow is untouched.
- `/glow self toggle` toggles only the local player's custom outline; registered players remain unaffected. The self setting persists after restarting, and the global toggle still overrides it.
- `/glow` shows current status.
- `/glow <player>` registers an online player by UUID; reusing a previously saved name also works.
- `/glow <player> <color>` registers or changes that player's RGB outline.
- `/glow color <color>` changes only your own color.
- `/glow remove <player>`, `/glow clear`, `/glow list` manage registered players.
- `/party glow toggle` independently enables/disables automatic outlines for Party HUD targets with the `TEAM` or `ENEMY` affinity; `/party glow` displays the setting. `/party <player> team` gives a soft lime outline (`#B6F27A`); `/party <player> enemy` gives a coral red outline (`#FF6577`). Neutral or removed party targets have no party-specific outline. Party affiliation overrides manual per-player Glow while party Glow is ON; switching party Glow OFF restores manual colors when standalone Glow is enabled.
- Party Glow defaults OFF on existing installations; its state is persisted as `partyGlowEnabled` in `config/152_hud.json` alongside the existing party UUIDs and affinities, not in the standalone `152_glow.json`. Party Glow does not require `/glow toggle` to be ON.
- Color input: RRGGBB (e.g. FF77AA), 0xRRGGBB, or a name (red, green, blue, yellow, aqua, cyan, pink, purple, orange, lime, white, black, gray, lightgray). Use an unprefixed 6-digit hex value in commands for widest Brigadier compatibility.
- Custom Glow starts OFF; run `/glow toggle` once to enable it. Registering a player while OFF retains the target until Glow is toggled ON.
- Saved under `config/152_glow.json` with `enabled`, `selfEnabled`, `selfColor`, and `players` (UUID -> name/color). The UUID list and self preference persist across sessions even while Glow is OFF. Existing files without `selfEnabled` default to true for backward compatibility.
- Only player entities loaded and rendered by this client can show an outline. No packets or server glow flags are modified.

## Skin and Cape
- `/skin <player>` replaces only your local player's skin with an online player's or Mojang-provided skin.
- `/skin reset` restores just the original skin.
- `/cape <name>` selects a built-in cape name, `/cape reset` restores just the original cape.
- Cape override also applies to the elytra texture, as in Churrrrr.
- `config/152_cosmetics.json` stores both selections, which are reapplied on the next launch.
- Skin/cape are client-side visual overrides, not account ownership or changes visible to other clients.
- Cape URLs or Mojang requests can fail; Lunar Client cosmetics may render separately and need testing.

## Build and compatibility
Minecraft 1.21.4 + Yarn 1.21.4+build.8, Fabric client. Java 21.
When testing with Lunar Client, disable duplicate cosmetic/outline features if they interfere.
Run `./gradlew build` and verify regular Fabric / Lunar rendering in the actual client.
