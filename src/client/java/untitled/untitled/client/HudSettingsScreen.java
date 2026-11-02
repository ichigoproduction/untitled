package untitled.untitled.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Vanilla-style, two-column HUD settings menu.
 * All labels are English and all settings use the existing HUD persistence.
 */
public final class HudSettingsScreen extends Screen {
    private static final String[] NAMES = {
        "HUD Position", "Skill HUD", "Food HUD", "Drink HUD", "Syringe HUD", "Title HUD"
    };
    private static final String[] DESCRIPTIONS = {
        "Move on-screen widgets", "Skill cooldown display", "Food stack tracker",
        "Drink stack tracker", "Syringe cooldown", "Title and subtitle"
    };
    private static final int BUTTON_WIDTH = 150;
    private static final int BUTTON_HEIGHT = 20;
    private static final int COLUMN_GAP = 10;
    private static final int ROW_GAP = 5;
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
        int left = width / 2 - (BUTTON_WIDTH * 2 + COLUMN_GAP) / 2;
        int top = 48;
        if (category < 0) {
            for (int i = 0; i < NAMES.length; i++) {
                final int target = i;
                int x = left + (i % 2) * (BUTTON_WIDTH + COLUMN_GAP);
                int y = top + (i / 2) * (BUTTON_HEIGHT + ROW_GAP);
                addDrawableChild(ButtonWidget.builder(Text.literal(NAMES[i]),
                    button -> client.setScreen(new HudSettingsScreen(parent, target)))
                    .dimensions(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
            }
        } else {
            if (category == 0 || category == 5) {
                addDrawableChild(ButtonWidget.builder(Text.literal("Edit Positions"),
                    button -> {
                        client.setScreen(null);
                        if (category == 0) EditHud.openSettingsEditor();
                        else TitleHud.openSettingsEditor();
                    }).dimensions(left, top, BUTTON_WIDTH, BUTTON_HEIGHT).build());
            }
            if (category != 0) {
                int toggleX = (category == 5) ? left + BUTTON_WIDTH + COLUMN_GAP : left;
                addDrawableChild(ButtonWidget.builder(Text.literal(toggleLabel()), button -> {
                    toggleCategory();
                    button.setMessage(Text.literal(toggleLabel()));
                }).dimensions(toggleX, top, BUTTON_WIDTH, BUTTON_HEIGHT).build());
            }
            addDrawableChild(ButtonWidget.builder(Text.literal("Back"),
                button -> client.setScreen(new HudSettingsScreen(parent)))
                .dimensions(width / 2 - BUTTON_WIDTH / 2, top + 2 * (BUTTON_HEIGHT + ROW_GAP),
                    BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"),
            button -> close()).dimensions(width / 2 - BUTTON_WIDTH / 2, height - 28,
                BUTTON_WIDTH, BUTTON_HEIGHT).build());
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
        context.drawCenteredTextWithShadow(textRenderer,
            category < 0 ? "untitled HUD Settings" : NAMES[category],
            width / 2, 18, 0xFFFFFF);
        if (category >= 0) {
            context.drawCenteredTextWithShadow(textRenderer,
                DESCRIPTIONS[category], width / 2, 33, 0xA0A0A0);
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
