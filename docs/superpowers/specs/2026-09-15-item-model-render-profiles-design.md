# Item Model Render Profiles Design

## Goal

Extend the existing client-side item model override system from a first-person-only static replacement into a scoped render-profile system that supports two-state sword draw/sheath behavior, local-player-only third-person rendering, GUI rendering, and equipped armor/head rendering while preserving existing commands and configuration compatibility.

## User-visible behavior

### Stateful sword behavior

A stateful copied model profile has two snapshots:

- `SHEATHED`: default/base model state.
- `DRAWN`: alternate model state.

Runtime semantics:

1. The target item starts in `SHEATHED`.
2. One successful client attack attempt while the target is in the local player's main hand changes that target to `DRAWN`.
3. Further attacks do not toggle it back.
4. Changing the selected hotbar slot, removing the target item from the local player's main hand, disconnecting/changing world, or otherwise replacing the held target resets it to `SHEATHED`.
5. This state machine is independent of the target item's own `custom_model_data`; the target may remain at a constant value such as `42`.

The attack event is observed from `MinecraftClient#doAttack()` so the mod does not consume or interfere with the vanilla attack key queue.

### Render scopes

Each model rule/profile stores these independent scope flags:

- `first_right`
- `first_left`
- `third_right`
- `third_left`
- `gui`
- `equipment`

Scope semantics:

- `first_right` / `first_left`: local player's first-person hand rendering.
- `third_right` / `third_left`: only the local player's third-person hand rendering. Other players are never changed by these scopes.
- `gui`: inventory, hotbar, container/shop GUI and other `GUI` transformation rendering. GUI always uses the `SHEATHED` snapshot even when the runtime hand state is `DRAWN`.
- `equipment`: local player's equipped head/chest/legs/feet rendering and local-player `HEAD` item-model rendering. Equipment always uses the `SHEATHED` snapshot.

Ground/fixed/item-frame rendering is out of scope for this change.

### Scope command UX

Use toggle-only scope commands:

```text
/imodel scope <target> first_right toggle
/imodel scope <target> first_left toggle
/imodel scope <target> third_right toggle
/imodel scope <target> third_left toggle
/imodel scope <target> gui toggle
/imodel scope <target> equipment toggle
/imodel scope <target> all toggle
```

`<target>` uses Brigadier string parsing so a single-token name may be unquoted and a name with spaces may be quoted.

`all toggle` behavior:

- If all six scopes are enabled, turn all six off.
- Otherwise turn all six on.

Default scopes for new and legacy rules preserve current behavior:

- `first_right = true`
- `first_left = true`
- all other scopes `false`

### Stateful capture commands

Preserve existing static behavior:

```text
/imodelcopyfrom <source> <target>
```

Add optional state capture:

```text
/imodelcopyfrom <source> <target> sheathed
/imodelcopyfrom <source> <target> drawn
```

The source is resolved from the automatic item model cache at command execution time. This lets the user capture the same source display name twice after the server changes its component state, for example CMD `17` as `sheathed` and CMD `18` as `drawn`.

Rules:

- `sheathed` creates or updates the base snapshot for the target.
- `drawn` stores the alternate snapshot. If no sheathed/base snapshot exists for that target, return an error rather than creating an unusable stateful profile.
- A profile becomes dynamically stateful only when it has both a sheathed/base snapshot and a drawn snapshot.
- Existing two-argument `/imodelcopyfrom` remains a static copied-stack rule.

## Internal architecture

### Persistent model profile

Replace the effective single copied payload with a profile that can retain:

- rule kind (`VANILLA_ITEM` or copied stack)
- base/static payload
- optional drawn payload
- description/source metadata
- six render-scope flags

Existing `152_models.json` entries lacking the new fields are loaded with compatibility defaults. No migration command is required.

### Runtime state

Introduce a focused runtime-state helper responsible for:

- the currently drawn target display name, if any
- selected hotbar slot tracking
- detecting main-hand target replacement
- resetting on disconnect/world/player loss
- accepting an attack notification from the Minecraft client mixin

Runtime state is not persisted.

### Rendering

Keep rendering client-only; never mutate the actual server-backed inventory stack.

1. **First person**: retain the `HeldItemRenderer` hook, but make it scope-aware and state-aware for left/right hand.
2. **Third person**: hook the biped/armed render-state item-model update path and substitute only when the rendered entity is `MinecraftClient.getInstance().player`. Use `THIRD_PERSON_RIGHT_HAND` / `THIRD_PERSON_LEFT_HAND` scope and runtime state.
3. **GUI**: hook the item render/model update path for `ModelTransformationMode.GUI`. Entity is not required. Resolve to the sheathed/base snapshot only.
4. **Equipment**: after the local player's `PlayerEntityRenderState` is populated, replace `equippedHeadStack`, `equippedChestStack`, `equippedLegsStack`, and `equippedFeetStack` with sheathed/base render copies for matching `equipment`-enabled profiles. Do not change other players' render states.
5. **HEAD item mode**: when an item is rendered with `ModelTransformationMode.HEAD` for the local player, gate replacement behind the `equipment` scope and use sheathed/base state.

### Decoding and caches

Continue storing full stack SNBT payloads. Decoded render copies may be cached by target/profile state and invalidated when the dynamic registry manager changes or a rule changes.

## Compatibility

- Minecraft `1.21.4`
- Yarn `1.21.4+build.8`
- Fabric Loader `0.19.3`
- Fabric API `0.119.4+1.21.4`
- Java `21`
- Existing `/imodel`, `/imodelcopy`, `/imodelcopyfrom <source> <target>`, `/imodelcache`, and `/imodelinspect` behavior remains available.
- Existing model JSON config loads without manual edits.

## Failure behavior

- Unknown target in `/imodel scope` -> error.
- Unknown scope -> Brigadier does not offer/accept it.
- `drawn` capture without an existing base/sheathed profile -> error.
- Missing source cache entry -> existing cache-not-found error.
- Failed SNBT decode -> render original target stack as fallback.
- Non-local entities -> never receive third-person/equipment substitutions.

## Verification criteria

1. Legacy first-person static model rules still render as before.
2. Stateful source snapshots such as CMD 17/18 can be captured into one target profile.
3. Target item with unchanged own CMD still moves `SHEATHED -> DRAWN` after one local attack.
4. State remains `DRAWN` through further attacks while continuously held.
5. Changing hotbar slot resets state; returning to the item shows `SHEATHED`.
6. GUI always shows `SHEATHED` regardless of runtime state.
7. Third-person scopes affect only the local player's rendered hands.
8. Equipment scope affects only local player's equipped slots/head item render.
9. Each scope can be toggled independently in-game; `all toggle` follows the defined aggregate semantics.
10. Old config files load with first-person scopes enabled and new scopes disabled.
