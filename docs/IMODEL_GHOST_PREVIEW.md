# /imodel get – client-only ghost inventory preview

- `/imodel get minecraft:diamond_sword 1`: preview a vanilla item.
- `/imodel get "cached server item name" 32`: preview a previously observed server item (requires the item to be in 152_item_model_cache.json).
- `/imodel get "saved imodel rule name" 1`: use a rule from 152_models.json. Rules override cached items when names collide.
- `/imodel get clear`: clear player ghosts, the 54-slot virtual chest, and the cursor.

Previews occupy the selected empty hotbar slot first, then other empty player slots. All nine hotbar slots render their icons and counts while the inventory is closed. When the 36 player slots are full, previews overflow into the **shared 54-slot virtual chest**. Selecting an empty hotbar slot with a ghost still renders the hand model in first and third person.

Open a chest, barrel, ender chest, shulker box, hopper, or dispenser/dropper to access the virtual chest entries over empty container slots. Left-click picks up/places a ghost between player slots and container slots; right-click splits/places one; Shift-click moves ghosts between player and container regions; number keys exchange a ghost with an empty hotbar slot. Pressing Q discards only the preview. While holding a virtual ghost cursor, interactions with real items, unsupported slots, and drag gestures are blocked. Closing the GUI returns the ghost cursor to free preview space (or retains it until the next GUI if full).

Ghosts are render-only: no PlayerInventory.setStack calls, slot-change packets, or real item use. The virtual chest is a **shared visual preview collection**, not a real chest or a per-block inventory: the same 54 slots are shown in every supported container UI, and they reset when the world changes. Real server items take display priority. A chest ghost behind a real item is hidden until that slot is empty or another chest is opened; no actual chest contents are changed. Player ghosts are cleared when a real item enters the same player slot.

Limitations: custom models depend on the installed resource pack; cached component registries must decode in the current world. Container overlay is limited to chest-like handlers: generic chests/barrels/ender chests, shulkers, hoppers, and dispensers/droppers. Crafting, furnace, trading, creative wrapper and arbitrary modded GUI slots are excluded. Existing 152_models.json and 152_item_model_cache.json formats are unchanged.

Implementation files: GhostItemPreview.java, GhostHandledScreenMixin.java, GhostHotbarMixin.java, GhostFirstPersonMixin.java.
