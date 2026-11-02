package untitled.untitled.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.List;

/** Party and model configuration using vanilla controls. */
public final class AdditionalSettingsScreen extends Screen {
    private final Screen parent;
    private final String page;
    private TextFieldWidget first, second;
    private String feedback = "";
    private int index = 0;
    private String state = "normal";

    public AdditionalSettingsScreen(Screen parent, String page) {
        super(Text.literal(page));
        this.parent = parent;
        this.page = page;
    }
    private void button(String label, int x, int y, Runnable action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> action.run())
                .dimensions(x,y,150,20).build());
    }
    private void fields(int y) {
        first = new TextFieldWidget(textRenderer, width / 2 - 155, y, 150, 20, Text.literal("Name"));
        second = new TextFieldWidget(textRenderer, width / 2 + 5, y, 150, 20, Text.literal("Value"));
        first.setMaxLength(128); second.setMaxLength(128);
        addDrawableChild(first); addDrawableChild(second);
    }
    private void open(String p) { client.setScreen(new AdditionalSettingsScreen(this,p)); }
    private void refresh() { clearAndInit(); }
    private String selected(List<String> items) {
        if (items.isEmpty()) return "";
        index = Math.floorMod(index,items.size());
        return items.get(index);
    }
    private void cycle(List<String> items) {
        if (!items.isEmpty()) index = (index + 1) % items.size();
        refresh();
    }
    @Override protected void init() {
        super.init();
        int l = width/2 - 155, r = width/2 + 5, y=48;
        switch(page) {
            case "Party HUD" -> {
                button("Mode: "+PartyHud.settingsMode(), l,y,()->{ PartyHud.cycleSettingsMode();refresh(); });
                button("Sort: "+PartyHud.settingsSort(), r,y,()->{ PartyHud.cycleSettingsSort();refresh(); });
                button("Party Glow: "+(PartyHud.settingsGlow()?"ON":"OFF"),l,y+25,()->{ PartyHud.toggleSettingsGlow();refresh(); });
                button("Manage Players",r,y+25,()->open("Manage Players"));
                button("Clear All Players",l,y+50,()->open("Clear Party Confirmation"));
            }
            case "Manage Players" -> {
                fields(y);
                button("Add Team",l,y+25,()->updatePlayer("team"));
                button("Add Enemy",r,y+25,()->updatePlayer("enemy"));
                button("Add Neutral",l,y+50,()->updatePlayer("neutral"));
                button("Remove Name",r,y+50,()->{
                    feedback=PartyHud.settingsRemovePlayer(first.getText())?"Removed":"Not found";refresh();
                });
                List<String> players=PartyHud.settingsPlayers();
                button("Player: "+selected(players),l,y+75,()->cycle(players));
                button("Remove Selected",r,y+75,()->{
                    String p=selected(players);if(!p.isEmpty()) PartyHud.settingsRemovePlayer(p.split(" \\(")[0]);refresh();
                });
            }
            case "Clear Party Confirmation" -> {
                button("Confirm Clear Players",l,y,()->{PartyHud.settingsClearPlayers();client.setScreen(parent);});
                button("Cancel",r,y,()->client.setScreen(parent));
            }
            case "Item Model" -> {
                button("Models: "+(ItemModelOverrides.settingsEnabled()?"ON":"OFF"),l,y,()->{ItemModelOverrides.toggleSettingsEnabled();refresh();});
                button("Manage Models",r,y,()->open("Manage Models"));
                button("Copy Held Item",l,y+25,()->open("Copy Held Item"));
                button("Copy From Cache",r,y+25,()->open("Copy From Cache"));
                button("Model Cache",l,y+50,()->open("Model Cache"));
                button("Render Settings",r,y+50,()->open("Render Settings"));
                button("Clear All Models",l,y+75,()->open("Clear Models Confirmation"));
                button("Ghost Items: use /imodel get",r,y+75,()->{feedback="Ghost item command remains available";});
            }
            case "Manage Models" -> {
                fields(y);
                List<String> models=ItemModelOverrides.settingsNames();
                button("Selected: "+selected(models),l,y+25,()->cycle(models));
                button("Remove Selected",r,y+25,()->{
                    ItemModelOverrides.settingsRemove(selected(models));refresh();
                });
                button("Map Name to Item ID",l,y+50,()->{
                    feedback=ItemModelOverrides.settingsMapVanilla(first.getText(),second.getText())?"Saved":"Invalid name or item ID";
                });
            }
            case "Copy Held Item" -> {
                fields(y);
                button("Save Held as Name",l,y+25,()->{
                    feedback=ItemModelOverrides.settingsCopyHeld(first.getText())?"Saved":"Hold an item and enter a name";
                });
            }
            case "Copy From Cache" -> {
                fields(y);
                button("State: "+state,l,y+25,()->{
                    state=state.equals("normal")?"sheathed":state.equals("sheathed")?"drawn":"normal";refresh();
                });
                button("Copy Cache to Target",r,y+25,()->{
                    feedback=ItemModelOverrides.settingsCopyCache(first.getText(),second.getText(),state)?"Saved":"Cache missing or invalid target/state";
                });
                button("Source: "+selected(ItemModelCache.settingsEntries()),l,y+50,
                    ()->cycle(ItemModelCache.settingsEntries()));
                button("Use Selected Source",r,y+50,()->first.setText(selected(ItemModelCache.settingsEntries())));
            }
            case "Render Settings" -> {
                List<String> models=ItemModelOverrides.settingsNames();
                String name=selected(models);
                button("Model: "+name,l,y,()->cycle(models));
                List<String> scopes=ItemModelOverrides.settingsScopes();
                for(int i=0;i<scopes.size();i++){
                    String scope=scopes.get(i);
                    button(scope+": "+(ItemModelOverrides.settingsScopeEnabled(name,scope)?"ON":"OFF"),
                        l+(i%2)*160,y+25+(i/2)*25,()->{ItemModelOverrides.toggleSettingsScope(name,scope);refresh();});
                }
            }
            case "Model Cache" -> {
                button("Auto Cache: "+(ItemModelCache.settingsAutoCache()?"ON":"OFF"),l,y,()->{
                    ItemModelCache.toggleSettingsAutoCache();refresh();
                });
                List<String> items=ItemModelCache.settingsEntries();
                button("Cached: "+selected(items),r,y,()->cycle(items));
                button("Remove Selected Cache",l,y+25,()->{ItemModelCache.settingsRemove(selected(items));refresh();});
                button("Clear Cache",r,y+25,()->open("Clear Cache Confirmation"));
            }
            case "Clear Cache Confirmation" -> {
                button("Confirm Clear Cache",l,y,()->{ItemModelCache.settingsClear();client.setScreen(parent);});
                button("Cancel",r,y,()->client.setScreen(parent));
            }
            case "Clear Models Confirmation" -> {
                button("Confirm Clear Models",l,y,()->{ItemModelOverrides.settingsClear();client.setScreen(parent);});
                button("Cancel",r,y,()->client.setScreen(parent));
            }
            default -> {}
        }
        button("Back",width/2-75,height-28,()->client.setScreen(parent));
    }
    private void updatePlayer(String role) {
        feedback=PartyHud.settingsAddPlayer(first.getText(),role);
    }
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        super.render(ctx,mx,my,delta);
        ctx.drawCenteredTextWithShadow(textRenderer,page,width/2,18,0xFFFFFF);
        if(!feedback.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer,feedback,width/2,height-55,0xAAAAAA);
    }
    @Override public void close(){client.setScreen(parent);}
}
