package untitled.untitled.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable, scrollable item list shared by all model-related screens. */
public final class ModelSelectionScreen extends Screen {
    private final Screen parent;
    private final String heading;
    private final List<String> all;
    private final Consumer<String> selected;
    private TextFieldWidget search;
    private String filter = "";
    private int scroll = 0;
    private List<String> filtered = List.of();

    public ModelSelectionScreen(Screen parent, String heading, List<String> options, Consumer<String> selected) {
        super(Text.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.all = new ArrayList<>(options);
        this.all.sort(String.CASE_INSENSITIVE_ORDER);
        this.selected = selected;
    }

    private int rows() { return Math.max(1, (height - 112) / 23); }

    private void rebuildResults() {
        String needle = filter.toLowerCase(Locale.ROOT);
        filtered = all.stream().filter(n -> n.toLowerCase(Locale.ROOT).contains(needle)).toList();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, filtered.size() - rows())));
    }

    private void rebuildRows() {
        clearAndInit();
    }

    @Override
    protected void init() {
        super.init();
        rebuildResults();
        search = new TextFieldWidget(textRenderer, width / 2 - 155, 37, 310, 20, Text.literal("Search"));
        search.setMaxLength(128);
        search.setText(filter);
        search.setChangedListener(value -> {
            filter = value;
            scroll = 0;
            rebuildRows();
            setFocused(search);
        });
        addDrawableChild(search);
        for (int i = scroll; i < Math.min(filtered.size(), scroll + rows()); i++) {
            String name = filtered.get(i);
            int y = 64 + (i - scroll) * 23;
            addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> {
                selected.accept(name);
                client.setScreen(parent);
            }).dimensions(width / 2 - 155, y, 310, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
            .dimensions(width / 2 - 75, height - 27, 150, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int shift = verticalAmount > 0 ? -3 : verticalAmount < 0 ? 3 : 0;
        int next = Math.max(0, Math.min(scroll + shift, Math.max(0, filtered.size() - rows())));
        if (next != scroll) {
            scroll = next;
            rebuildRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, heading, width / 2, 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
            filtered.size() + " matching / " + all.size() + " items", width / 2, 25, 0xAAAAAA);
    }

    @Override public void close() { client.setScreen(parent); }
}
