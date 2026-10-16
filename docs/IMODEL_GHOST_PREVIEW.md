# /imodel get – client-only ghost inventory preview

- `/imodel get minecraft:diamond_sword 1`: preview a vanilla item.
- `/imodel get "cached server item name" 32`: preview a previously observed server item (requires the item to be in 152_item_model_cache.json).
- `/imodel get "saved imodel rule name" 1`: use a rule from 152_models.json. Rules override cached items when names collide.
- `/imodel get clear`: remove all ghost stacks and the virtual cursor.

Previews occupy the selected empty hotbar slot first, then another empty hotbar/inventory slot. Open a normal inventory to see the ghost icon; select its hotbar slot to see the first-person and third-person hand model.

Left-click a ghost to move it on a virtual cursor; left-click an empty player-inventory slot to place it. Right-click splits/picks one, Shift-click moves to another empty slot, and Q on a ghost drops the preview. These are virtual operations only. A ghost cursor cannot click a real item or container slot. Drag gestures are intentionally blocked while a ghost cursor is active, and a ghost cursor is returned to an empty virtual slot on closing the inventory (or discarded if full).

Ghosts are render-only: there is no PlayerInventory.setStack, no slot-change packet, and no real item use, crafting, or server-side effect. Real server items always take priority; if the server fills a ghost-occupied slot the ghost is discarded. All previews are transient and cleared on world change/disconnection and by the clear command.

Limitations: custom models depend on the installed resource pack; cached component registries must decode in the current world. Creative inventory screen slot wrappers or other GUI-mod overlays may not display ghost slots. Existing /imodel rule settings and cache files are not changed.

Implementation files: GhostItemPreview.java, GhostHandledScreenMixin.java, GhostHotbarMixin.java, GhostFirstPersonMixin.java.
