# Item Model Render Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand item model overrides into scoped render profiles with sheathed/drawn runtime state, local-player-only third person, GUI base-state rendering, and local-player equipment rendering.

**Architecture:** Extend the persisted model rule into a profile with optional drawn payload and render scopes. Add a pure runtime-state helper driven by attack notifications and client ticks. Keep first-person replacement at the held-item renderer, add third-person substitution in armed render-state generation, GUI substitution in the item-renderer model-resolution call, and equipment substitution in local player render-state population.

**Tech Stack:** Java 21, Fabric Loader 0.19.3, Fabric API 0.119.4+1.21.4, Minecraft 1.21.4, Yarn 1.21.4+build.8, Sponge Mixin/Brigadier/Gson.

**Spec:** `docs/superpowers/specs/2026-09-15-item-model-render-profiles-design.md`

## Global Constraints

- Preserve existing `/imodel`, `/imodelcopy`, two-argument `/imodelcopyfrom`, `/imodelcache`, and `/imodelinspect` behavior.
- Existing `152_models.json` must load without migration.
- Default legacy/new scopes: first_right=true, first_left=true, all others=false.
- Third-person and equipment replacement must never affect other players.
- GUI and equipment always use the sheathed/base payload.
- Runtime drawn state is client-only and not persisted.
- Ground/fixed rendering remains unchanged.

---

### Task 1: Extend command parsing for optional state capture

**Files:**
- Modify: `src/client/java/untitled/untitled/client/ModelCommandParser.java`
- Create: `src/test/java/untitled/untitled/client/ModelCommandParserTest.java` only if a test source set exists; otherwise use the existing pure-Java parser harness pattern.

**Interfaces:**
- Produces: `CopyStateMapping(sourceName, targetName, state)` where state is nullable for legacy static copy.

- [ ] Write parser tests for `source target`, `source target sheathed`, `source target drawn`, and quoted names.
- [ ] Verify the new tests fail before implementation.
- [ ] Implement token parsing that accepts exactly two tokens or three tokens where token 3 is `sheathed` or `drawn`.
- [ ] Verify parser tests pass and prior parser cases remain green.

### Task 2: Extend persisted model rules into scoped profiles

**Files:**
- Modify: `src/client/java/untitled/untitled/client/ItemModelOverrides.java`

**Interfaces:**
- Add enum `RenderScope { FIRST_RIGHT, FIRST_LEFT, THIRD_RIGHT, THIRD_LEFT, GUI, EQUIPMENT }`.
- Model rule/profile stores static/base payload, optional drawn payload, description, and six booleans/scopes.
- Produce helpers `hasRule(String)`, `resolveFirstPersonStack(...)`, `resolveThirdPersonStack(...)`, `resolveGuiStack(...)`, `resolveEquipmentStack(...)`.

- [ ] Add compatibility-focused pure logic tests where feasible for default scopes and all-toggle semantics.
- [ ] Update rule representation with drawn payload and scope flags.
- [ ] Update JSON load/save so absent scope/drawn fields use compatibility defaults.
- [ ] Keep existing vanilla/static copied rules valid.
- [ ] Add stateful `/imodelcopyfrom` capture behavior: sheathed creates/updates base; drawn requires existing base.
- [ ] Add `/imodel scope <target> <scope> toggle` and `/imodel scope <target> all toggle`.
- [ ] Update `/imodel list` descriptions to expose state availability and enabled scopes without changing rule identity semantics.

### Task 3: Add runtime sheathed/drawn state machine

**Files:**
- Create: `src/client/java/untitled/untitled/client/ItemModelRuntime.java`
- Modify: `src/client/java/untitled/untitled/client/UntitledClient.java`

**Interfaces:**
- `init()` registers END_CLIENT_TICK reset tracking.
- `onAttack(MinecraftClient client)` transitions current main-hand stateful target to DRAWN.
- `isDrawn(String targetName)` reports runtime state.
- `reset()` clears runtime state.

- [ ] Implement pure transition logic around active target and selected slot.
- [ ] On attack, only set DRAWN for a main-hand target profile with a drawn snapshot.
- [ ] On selected hotbar slot change, player/world loss, or main-hand target replacement, clear DRAWN.
- [ ] Register runtime initialization in `UntitledClient`.

### Task 4: Capture vanilla attack without consuming key state

**Files:**
- Create: `src/client/java/untitled/untitled/client/mixin/MinecraftClientMixin.java`
- Modify: `src/main/resources/untitled.client.mixins.json`

**Interfaces:**
- Inject at HEAD of `MinecraftClient#doAttack()Z` and call `ItemModelRuntime.onAttack((MinecraftClient)(Object)this)`.

- [ ] Add the mixin with the exact Yarn 1.21.4 descriptor.
- [ ] Register it in the client mixin JSON.
- [ ] Confirm no cancellation or keybinding consumption is introduced.

### Task 5: Make first-person rendering scope/state aware

**Files:**
- Modify: `src/client/java/untitled/untitled/client/mixin/HeldItemRendererMixin.java`
- Modify: `src/client/java/untitled/untitled/client/ItemModelOverrides.java`

**Interfaces:**
- Resolve based on `ModelTransformationMode.FIRST_PERSON_RIGHT_HAND` / `FIRST_PERSON_LEFT_HAND` and current runtime state.

- [ ] Expand the existing `@ModifyArg` handler to consume all subject render-item arguments so the resolver sees transformation mode/entity.
- [ ] Respect independent right/left scope flags.
- [ ] Use drawn payload only for the target currently marked DRAWN; otherwise use sheathed/base.

### Task 6: Add local-player-only third-person hand replacement

**Files:**
- Create: `src/client/java/untitled/untitled/client/mixin/ArmedEntityRenderStateMixin.java`
- Modify: `src/main/resources/untitled.client.mixins.json`
- Modify: `src/client/java/untitled/untitled/client/ItemModelOverrides.java`

**Interfaces:**
- Intercept `ItemModelManager.updateForLivingEntity` arguments inside `ArmedEntityRenderState.updateRenderState`.
- Resolver receives `ModelTransformationMode`, handedness, and entity.

- [ ] Modify the stack argument passed into `updateForLivingEntity`.
- [ ] Return original stack unless entity identity equals `MinecraftClient.getInstance().player`.
- [ ] Respect third_right/third_left scopes and runtime drawn state.
- [ ] Register the mixin.

### Task 7: Add GUI base-state replacement

**Files:**
- Create: `src/client/java/untitled/untitled/client/mixin/ItemRendererMixin.java`
- Modify: `src/main/resources/untitled.client.mixins.json`
- Modify: `src/client/java/untitled/untitled/client/ItemModelOverrides.java`

**Interfaces:**
- Intercept the stack argument passed from the non-entity `ItemRenderer.renderItem(ItemStack, ModelTransformationMode, ...)` path into `ItemModelManager.update`.

- [ ] Gate only on `ModelTransformationMode.GUI` (plus local-player `HEAD` handling where entity context is available in the entity-aware overload).
- [ ] GUI replacement requires gui scope and always resolves sheathed/base.
- [ ] HEAD replacement requires equipment scope and local-player entity.
- [ ] Register the mixin.

### Task 8: Add local-player equipment replacement

**Files:**
- Create: `src/client/java/untitled/untitled/client/mixin/PlayerEntityRendererMixin.java`
- Modify: `src/main/resources/untitled.client.mixins.json`
- Modify: `src/client/java/untitled/untitled/client/ItemModelOverrides.java`

**Interfaces:**
- Inject TAIL into `PlayerEntityRenderer.updateRenderState(AbstractClientPlayerEntity, PlayerEntityRenderState, float)`.
- Replace `equippedHeadStack`, `equippedChestStack`, `equippedLegsStack`, `equippedFeetStack` only for the local player.

- [ ] Check player identity against the local client player.
- [ ] Replace each matching equipment stack through the equipment-scope resolver.
- [ ] Always use sheathed/base snapshot.
- [ ] Register the mixin.

### Task 9: Verification and regression review

**Files:**
- Review all changed Java/resource/config serialization code.

- [ ] Run parser/unit tests.
- [ ] Run `./gradlew clean build` (Windows equivalent `gradlew.bat clean build`) and require exit code 0 before claiming build success.
- [ ] Inspect final diff from pre-feature HEAD to feature HEAD; verify only model-system docs/source/tests/mixin config changed.
- [ ] Verify mixin descriptors against Yarn 1.21.4+build.8 API signatures.
- [ ] Confirm old JSON fields still load and missing new fields obtain compatibility defaults.
- [ ] Commit final implementation with a concise feature message.
