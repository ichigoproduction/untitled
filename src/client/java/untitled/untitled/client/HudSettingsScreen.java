package untitled.untitled.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** First iteration of the English HUD settings dashboard. */
public final class HudSettingsScreen extends Screen {
    private static final String[] NAMES = {
        "HUD Position", "Skill HUD", "Food HUD", "Drink HUD", "Syringe HUD", "Title HUD"
    };
    private static final String[] DESCRIPTIONS = {
        "Move on-screen widgets", "Skill cooldown display", "Food stack tracker",
        "Drink stack tracker", "Syringe cooldown", "Title and subtitle"
    };
    private final Screen parent;
    private final int category;

    public HudSettingsScreen(Screen parent) {
        this(parent, -1);
    }

    private HudSettingsScreen(Screen parent, int category) {
        super(Text.literal("untitled HUD Settings"));
        this.parent = parent;
        this.category = category;
    }

    @Override
    protected void init() {
        super.init();
        int left = this.width / 2 - 155;
        int top = Math.max(54, this.height / 2 - 88);
        if (category < 0) {
            for (int i = 0; i < NAMES.length; i++) {
                final int target = i;
                int x = left + (i % 2) * 160;
                int y = top + (i / 2) * 48;
                addDrawableChild(ButtonWidget.builder(Text.literal(NAMES[i]),
                    button -> client.setScreen(new HudSettingsScreen(parent, target)))
                    .dimensions(x, y, 150, 38).build());
            }
        } else {
            int center = width / 2;
            if (category == 0 || category == 5) {
                addDrawableChild(ButtonWidget.builder(Text.literal("Edit Positions"),
                    button -> {
                        client.setScreen(null);
                        if (category == 0) EditHud.openSettingsEditor();
                        else TitleHud.openSettingsEditor();
                    }).dimensions(center - 110, top + 36, 220, 24).build());
            }
            if (category != 0) {
                addDrawableChild(ButtonWidget.builder(Text.literal(toggleLabel()), button -> {
                    toggleCategory();
                    button.setMessage(Text.literal(toggleLabel()));
                }).dimensions(center - 110, top + 69, 220, 24).build());
            }
            addDrawableChild(ButtonWidget.builder(Text.literal("Back"),
                button -> client.setScreen(new HudSettingsScreen(parent)))
                .dimensions(center - 110, top + 112, 220, 24).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
            button -> close()).dimensions(width / 2 - 70, height - 34, 140, 20).build());
    }

    private String toggleLabel() {
        boolean enabled = switch (category) {
            case 1 -> SkillHud.settingsEnabled();
            case 2 -> FoodStack.foodSettingsEnabled();
            case 3 -> FoodStack.drinkSettingsEnabled();
            case 4 -> SyringeCooldownHud.settingsEnabled();
            case 5 -> TitleHud.settingsEnabled();
            default -> false;
        };
        return "Enabled: " + (enabled ? "ON" : "OFF");
    }

    private void toggleCategory() {
        switch (category) {
            case 1 -> SkillHud.toggleSettingsEnabled();
            case 2 -> FoodStack.toggleFoodSettings();
            case 3 -> FoodStack.toggleDrinkSettings();
            case 4 -> SyringeCooldownHud.toggleSettingsEnabled();
            case 5 -> TitleHud.toggleSettingsEnabled();
            default -> { }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = width / 2;
        context.drawCenteredTextWithShadow(textRenderer, "UNTITLED", cx, 20, 0xA8B8FF);
        context.drawCenteredTextWithShadow(textRenderer, 
            category < 0 ? "HUD SETTINGS" : NAMES[category], cx, 35, 0xFFFFFF);
        if (category < 0) {
            context.drawCenteredTextWithShadow(textRenderer,
                "Choose a module to configure", cx, height - 53, 0xA8ACB8);
        } else {
            context.drawCenteredTextWithShadow(textRenderer,
                DESCRIPTIONS[category], cx, Math.max(54, height / 2 - 88) + 10, 0xBFC5D3);
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
