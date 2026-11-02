package untitled.untitled.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Clickable named-color palette with prefix suggestions and direct HEX entry. */
public final class GlowColorScreen extends Screen {
    private static final List<String> COLORS = List.of(
        "white", "black", "red", "green", "blue", "yellow", "aqua",
        "cyan", "pink", "purple", "orange", "lime", "gray", "lightgray"
    );
    private final Screen parent;
    private final Predicate<String> apply;
    private final String heading;
    private TextFieldWidget input;
    private String typed = "";
    private String status = "";

    public GlowColorScreen(Screen parent, String heading, Predicate<String> apply) {
        super(Text.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.apply = apply;
    }

    private void submit(String color) {
        if (apply.test(color)) {
            status = "Saved: " + color;
            typed = color;
            if (input != null) input.setText(color);
        } else status = "Invalid color (name or RRGGBB)";
    }

    @Override protected void init() {
        super.init();
        int left = width / 2 - 155, right = width / 2 + 5;
        input = new TextFieldWidget(textRenderer, left, 39, 310, 20, Text.literal("Color name or HEX"));
        input.setMaxLength(32);
        input.setText(typed);
        input.setChangedListener(value -> {
            typed = value;
            updateSuggestionButtons();
        });
        addDrawableChild(input);
        updateSuggestionButtons();
    }

    private void updateSuggestionButtons() {
        // Called only on text changes, not every frame.
        clearPaletteButtons();
        String search = typed.trim().toLowerCase(Locale.ROOT);
        boolean isHex = search.startsWith("#") || search.startsWith("0x")
                || search.matches("[0-9a-f]{1,6}");
        int row = 0;
        for (String name : COLORS) {
            if (!search.isEmpty() && !isHex && !name.startsWith(search)) continue;
            final String chosen = name;
            int x = width / 2 - 155 + (row % 2) * 160;
            int y = 70 + (row / 2) * 24;
            addDrawableChild(ButtonWidget.builder(Text.literal(name), button -> submit(chosen))
                    .dimensions(x, y, 150, 20).build());
            row++;
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Apply Name / HEX"), b -> submit(input.getText()))
                .dimensions(width / 2 - 155, height - 52, 150, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
                .dimensions(width / 2 + 5, height - 52, 150, 20).build());
    }

    private void clearPaletteButtons() {
        // Rebuild only this screen's widget collection; preserve text field and its focus.
        clearChildren();
        if (input != null) addDrawableChild(input);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, heading, width / 2, 15, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
            status.isEmpty() ? "Select a color or enter #RRGGBB" : status,
            width / 2, height - 71, 0xAAAAAA);
    }

    @Override public void close() { client.setScreen(parent); }
}
