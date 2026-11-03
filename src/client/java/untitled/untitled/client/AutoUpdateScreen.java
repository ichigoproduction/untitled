package untitled.untitled.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** No automatic installation: the user must explicitly approve a download. */
public final class AutoUpdateScreen extends Screen {
    private final Screen parent;
    public AutoUpdateScreen(Screen parent) {
        super(Text.literal("Auto Update"));
        this.parent = parent;
    }
    @Override protected void init() {
        int x = width / 2 - 75;
        addDrawableChild(ButtonWidget.builder(Text.literal("Check for Updates"), b -> AutoUpdater.check())
            .dimensions(x, 60, 150, 20).build());
        ButtonWidget approve = ButtonWidget.builder(Text.literal("Download"), b -> AutoUpdater.approveDownload())
            .dimensions(x, 88, 150, 20).build();
        approve.active = AutoUpdater.hasUpdate() && !AutoUpdater.isBusy() && !AutoUpdater.isPrepared();
        addDrawableChild(approve);
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
            .dimensions(x, height - 28, 150, 20).build());
    }
    @Override public void tick() {
        // Refresh button state without rebuilding widgets and losing focus.
        if (children().size() > 1 && children().get(1) instanceof ButtonWidget approve)
            approve.active = AutoUpdater.hasUpdate() && !AutoUpdater.isBusy() && !AutoUpdater.isPrepared();
    }
    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx,mx,my,delta);
        ctx.drawCenteredTextWithShadow(textRenderer,"Auto Update",width/2,18,0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,"version : " + AutoUpdater.installedVersion(),width/2,128,0xAAAAAA);
    }
    @Override public void close() { client.setScreen(parent); }
}
