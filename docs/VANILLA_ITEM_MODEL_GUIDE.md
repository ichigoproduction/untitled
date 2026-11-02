# Vanilla Item Model Registration — User Guide

## Open the settings
1. Join a Minecraft 1.21.4 world with the mod enabled.
2. Type `/152` or open the mod's configuration through Mod Menu.
3. Choose **Item Model** → **Manage Models**.

## Register a vanilla item using search (recommended)
1. In the single text field, type the **target item display name**. This is the item you want to change the appearance of. For example, `My Sword`. For an existing item, enter its exact in-game display name.
2. Click **Set Model Name**. The text field is now used for the vanilla item ID.
3. Click **Select Vanilla Item**.
4. Search by item name or Minecraft ID. For example, `diamond` or `minecraft:diamond_sword`.
5. Scroll with the mouse wheel and click the desired item. The selected ID is populated into the previous screen's text field.
6. Click **Save Item ID**. You should see **Saved**.

**Example:** Target name `My Sword` → choose `Diamond Sword | minecraft:diamond_sword` → Save Item ID. When the held item's display name matches `My Sword`, the mod substitutes the selected vanilla model in enabled render scopes.

## Register using a manually typed ID
After setting the target model name, type a valid ID such as `minecraft:iron_sword` and click **Save Item ID**. The selected ID must exist in Minecraft's vanilla item registry. Modded namespaces are not supported by this particular action.

## Search controls
- The picker shows entries from Minecraft's runtime item registry, filtered to `minecraft:` namespace.
- Results are sorted alphabetically.
- Type in the search field to filter by item name or ID.
- Mouse wheel scrolls the filtered results.
- Clicking an entry selects it and returns to Manage Models.
- **Back** cancels selection.

## Manage existing model mappings
- **Select** opens a searchable list of mappings you saved.
- **Remove Selected** opens a confirmation screen.
- **Render Settings** (from Item Model) lets you choose a saved mapping and toggle first-person, third-person, GUI, and equipment render scopes.
- **Copy Held Item** copies the model of the item you're holding under a target name.
- **Copy From Cache** uses a cached item as the source, with Normal / Sheathed / Drawn options.

## Troubleshooting
- **Set model name first:** enter a nonempty target name and click **Set Model Name** before opening the vanilla picker.
- **Invalid item ID:** choose a valid `minecraft:` item from the picker or type its exact ID.
- **Nothing visually changes:** check **Item Model → Models: ON**, the target name's exact match, and **Render Settings**. A saved mapping does not change the original item on the server.
- **Cache is empty:** auto-cache defaults to OFF for fresh settings. Enable it only if you need cache capture. Any previously saved user setting takes precedence.
- **Existing mappings:** settings remain stored in `config/152_models.json`. Cache entries remain in `config/152_item_model_cache.json`.

## Notes
This is a client-side appearance feature, not a server-side item conversion. The vanilla picker does not require a manually maintained item list. The UI functionality and compile/runtime behavior should be tested after building the mod.
