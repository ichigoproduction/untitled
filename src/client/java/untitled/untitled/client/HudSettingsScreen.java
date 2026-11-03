package untitled.untitled.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.List;

/** English vanilla-style HUD configuration, also opened by /152. */
public final class HudSettingsScreen extends Screen {
    private static final String[] NAMES = {"HUD Position", "Food HUD", "Drink HUD", "Syringe HUD", "Skill HUD", "Title HUD"};
    private static final int W = 150, H = 20, GAP = 10;
    private final Screen parent;
    private final int category;
    private int skillPage;

    public HudSettingsScreen(Screen parent) { this(parent, -1); }
    private HudSettingsScreen(Screen parent, int category) {
        super(Text.literal("Made by 1_5_2"));
        this.parent = parent;
        this.category = category;
    }

    @Override
    protected void init() {
        super.init();
        int left = width / 2 - W - GAP / 2;
        if (category < 0) {
            for (int i = 0; i < NAMES.length; i++) {
                final int target = i;
                addButton(NAMES[i], left, 48 + i * 25,
                    () -> client.setScreen(new HudSettingsScreen(parent, target)));
            }
            addButton("Party HUD", left + W + GAP, 48,
                () -> client.setScreen(new AdditionalSettingsScreen(this, "Party HUD")));
            addButton("Item Model", left + W + GAP, 73,
                () -> client.setScreen(new AdditionalSettingsScreen(this, "Item Model")));
            addButton("Glow", left + W + GAP, 98,
                () -> client.setScreen(new AdditionalSettingsScreen(this, "Glow")));
            addButton("Skin & Cape", left + W + GAP, 123,
                () -> client.setScreen(new AdditionalSettingsScreen(this, "Skin & Cape")));
            addButton("Optimizer", left + W + GAP, 148,
                () -> client.setScreen(new AdditionalSettingsScreen(this, "Optimizer")));
            addButton("Kill Effect", left + W + GAP, 173,
                () -> client.setScreen(new AdditionalSettingsScreen(this, "Kill Effect")));
            addButton("Auto Update", left + W + GAP, 198,
                () -> client.setScreen(new AutoUpdateScreen(this)));
        } else {
            buildDetails(left);
        }
        addButton(category < 0 ? "Done" : "Back", width / 2 - W / 2, height - 28,
            () -> client.setScreen(category < 0 ? parent : new HudSettingsScreen(parent)));
    }

    private void addButton(String label, int x, int y, Runnable action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> action.run()).dimensions(x, y, W, H).build());
    }

    private void buildDetails(int left) {
        int y = 48;
        switch (category) {
            case 0 -> {
                addButton("Edit All Positions", left, y, () -> {
                    client.setScreen(null); EditHud.openSettingsEditor();
                });
                addButton("Reset All Positions", left + W + GAP, y, EditHud::resetSettingsPositions);
            }
            case 1 -> {
                addButton("Enabled: " + on(FoodStack.foodSettingsEnabled()), left, y, () -> { FoodStack.toggleFoodSettings(); refresh(); });
                addButton("Stacks: " + FoodStack.settingsFoodSegments(), left + W + GAP, y, () -> { FoodStack.toggleSettingsFoodSegments(); refresh(); });
                addButton("Layout: " + (FoodStack.settingsFoodVertical() ? "Vertical" : "Horizontal"), left, y+25,
                    () -> { FoodStack.toggleSettingsFoodLayout(); refresh(); });
                addButton("Color: " + FoodStack.settingsColor(true), left + W + GAP, y+25,
                    () -> { FoodStack.cycleSettingsColor(true); refresh(); });
                addButton("Shape: " + FoodStack.settingsShape(true), left, y+50,
                    () -> { FoodStack.cycleSettingsShape(true); refresh(); });
                addButton("Reset Stack", left + W + GAP, y+50, () -> { FoodStack.resetSettingsStack(true); refresh(); });
            }
            case 2 -> {
                addButton("Enabled: " + on(FoodStack.drinkSettingsEnabled()), left, y, () -> { FoodStack.toggleDrinkSettings(); refresh(); });
                addButton("Color: " + FoodStack.settingsColor(false), left + W + GAP, y,
                    () -> { FoodStack.cycleSettingsColor(false); refresh(); });
                addButton("Shape: " + FoodStack.settingsShape(false), left, y+25,
                    () -> { FoodStack.cycleSettingsShape(false); refresh(); });
                addButton("Reset Stack", left + W + GAP, y+25, () -> { FoodStack.resetSettingsStack(false); refresh(); });
            }
            case 3 -> {
                addButton("Enabled: " + on(SyringeCooldownHud.settingsEnabled()), left, y,
                    () -> { SyringeCooldownHud.toggleSettingsEnabled(); refresh(); });
                addButton("Size: " + percent(SyringeCooldownHud.settingsScale()), left + W + GAP, y,
                    () -> { SyringeCooldownHud.setSettingsScale(nextScale(SyringeCooldownHud.settingsScale())); refresh(); });
            }
            case 4 -> {
                addButton("Enabled: " + on(SkillHud.settingsEnabled()), left, y, () -> { SkillHud.toggleSettingsEnabled(); refresh(); });
                addButton("Size: " + percent(SkillHud.settingsScale()), left + W + GAP, y, () -> {
                    SkillHud.setSettingsScale(nextScale(SkillHud.settingsScale())); refresh();
                });
                addButton("Reset Skill Filters", left, y + 25, () -> { SkillHud.resetSettingsSkills(); refresh(); });
                List<String> skills = SkillHud.settingsSkills();
                int perPage = Math.max(2, ((height - 154) / 25) * 2);
                int pages = Math.max(1, (skills.size() + perPage - 1) / perPage);
                skillPage = Math.min(skillPage, pages - 1);
                for (int i = skillPage * perPage, j = 0; i < skills.size() && j < perPage; i++, j++) {
                    final String item = skills.get(i);
                    String key = item.substring(0, item.indexOf(" : "));
                    String label = item.substring(item.indexOf(" : ") + 3);
                    String caption = label + ": " + on(SkillHud.settingsSkillVisible(key));
                    addButton(caption, left + (j % 2) * (W + GAP), y + 50 + (j / 2) * 25,
                        () -> { SkillHud.toggleSettingsSkill(key); refresh(); });
                }
                addButton("Skills Page " + (skillPage + 1) + "/" + pages, left, height - 53,
                    () -> { skillPage = (skillPage + 1) % pages; refresh(); });
            }
            case 5 -> {
                addButton("Title: " + on(TitleHud.settingsTitleVisible()), left, y,
                    () -> { TitleHud.toggleSettingsTitle(); refresh(); });
                addButton("Subtitle: " + on(TitleHud.settingsSubtitleVisible()), left + W + GAP, y,
                    () -> { TitleHud.toggleSettingsSubtitle(); refresh(); });
                addButton("Title Size: " + percent(TitleHud.settingsTitleSize()), left, y+25,
                    () -> { TitleHud.setSettingsTitleSize(nextScale(TitleHud.settingsTitleSize())); refresh(); });
                addButton("Subtitle Size: " + percent(TitleHud.settingsSubtitleSize()), left + W + GAP, y+25,
                    () -> { TitleHud.setSettingsSubtitleSize(nextScale(TitleHud.settingsSubtitleSize())); refresh(); });
            }
            default -> {}
        }
    }

    private void refresh() { clearAndInit(); }
    private static String on(boolean enabled) { return enabled ? "ON" : "OFF"; }
    private static String percent(float scale) { return Math.round(scale * 100) + "%"; }
    private static float nextScale(float current) { return current >= 2.0f ? 0.5f : Math.round((current + 0.1f) * 10f) / 10f; }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer,
            category < 0 ? "Made by 1_5_2" : NAMES[category], width / 2, 18, 0xFFFFFF);
    }

    @Override
    public void close() { client.setScreen(parent); }
}
