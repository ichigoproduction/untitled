package untitled.untitled.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Dedicated title/subtitle position editor; does not touch /hud. */
public final class TitleHudScreen extends Screen {
    private enum Target { NONE, TITLE, SUBTITLE }

    private Target dragging = Target.NONE;
    private double lastMouseX;
    private double lastMouseY;

    TitleHudScreen() {
        super(Text.empty());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x88000000);
        TitleHud.renderEditorPreview(context);
        drawOutline(context, TitleHud.getTitleEditorBounds(), dragging == Target.TITLE);
        drawOutline(context, TitleHud.getSubtitleEditorBounds(), dragging == Target.SUBTITLE);
        context.drawCenteredTextWithShadow(textRenderer,
                "Drag TITLE or SUBTITLE to move (ESC to save)", width / 2, 15, 0xFFFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawOutline(DrawContext context, EditHud.HudBounds b, boolean selected) {
        int color = selected ? 0xFFEAD075 : 0x99FFFFFF;
        int x1 = b.x() - 4, y1 = b.y() - 4;
        int x2 = b.x() + b.width() + 4, y2 = b.y() + b.height() + 4;
        context.fill(x1, y1, x2, y1 + 1, color);
        context.fill(x1, y2 - 1, x2, y2, color);
        context.fill(x1, y1, x1 + 1, y2, color);
        context.fill(x2 - 1, y1, x2, y2, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            // Subtitle is drawn after title, so it wins on overlap.
            if (TitleHud.getSubtitleEditorBounds().contains(mouseX, mouseY)) {
                dragging = Target.SUBTITLE;
            } else if (TitleHud.getTitleEditorBounds().contains(mouseX, mouseY)) {
                dragging = Target.TITLE;
            }
            if (dragging != Target.NONE) {
                lastMouseX = mouseX;
                lastMouseY = mouseY;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY,
                                int button, double deltaX, double deltaY) {
        if (button == 0 && dragging != Target.NONE) {
            int dx = (int) Math.round(mouseX - lastMouseX);
            int dy = (int) Math.round(mouseY - lastMouseY);
            if (dx != 0 || dy != 0) {
                TitleHud.moveBy(dragging == Target.TITLE, dx, dy);
                lastMouseX = mouseX;
                lastMouseY = mouseY;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging != Target.NONE) {
            dragging = Target.NONE;
            EditHud.saveSettings();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        dragging = Target.NONE;
        EditHud.saveSettings();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
