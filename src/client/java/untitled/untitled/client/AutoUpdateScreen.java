package untitled.untitled.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** No automatic installation: the user must explicitly approve a download. */
public final class AutoUpdateScreen extends Screen {
    private final Screen parent;
    private ButtonWidget checkButton;
    private ButtonWidget downloadButton;
    private boolean manualCheck;
    private boolean downloadClicked;
    public AutoUpdateScreen(Screen parent) {
        super(Text.literal("Auto Update"));
        this.parent = parent;
    }
    @Override protected void init() {
        int x = width / 2 - 75;
        checkButton = addDrawableChild(ButtonWidget.builder(Text.literal("Check for Updates"), b -> {
            manualCheck = true;
            AutoUpdater.check();
            refreshButtons();
        }).dimensions(x, 60, 150, 20).build());
        downloadButton = addDrawableChild(ButtonWidget.builder(Text.literal("Download"), b -> {
            downloadClicked = true;
            AutoUpdater.approveDownload();
            refreshButtons();
        }).dimensions(x, 88, 150, 20).build());
        refreshButtons();
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> close())
            .dimensions(x, height - 28, 150, 20).build());
    }
    @Override public void tick() {
        refreshButtons();
    }

    private void refreshButtons() {
        if (checkButton == null || downloadButton == null) return;
        String status = AutoUpdater.status();
        checkButton.active = !AutoUpdater.isBusy();
        String checkLabel = "Check for Updates";
        if (manualCheck) {
            if (AutoUpdater.isBusy() && status.startsWith("Checking")) {
                checkLabel = "Checking...";
            } else if (status.startsWith("Update check failed")) {
                checkLabel = "Check Failed - Retry";
            } else if (AutoUpdater.hasUpdate()) {
                checkLabel = "Available: v" + AutoUpdater.availableVersion();
            } else if (status.startsWith("Up to date")) {
                checkLabel = "Up to Date";
            }
        }
        checkButton.setMessage(Text.literal(checkLabel));

        downloadButton.active = AutoUpdater.hasUpdate() && !AutoUpdater.isBusy() && !AutoUpdater.isPrepared();
        String downloadLabel = "Download";
        if (downloadClicked) {
            if (status.startsWith("Downloading")) downloadLabel = "Downloading...";
            else if (status.startsWith("Verified")) downloadLabel = "Downloaded - Restart";
            else if (status.startsWith("Update failed")) downloadLabel = "Download Failed - Retry";
        }
        downloadButton.setMessage(Text.literal(downloadLabel));
    }

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx,mx,my,delta);
        ctx.drawCenteredTextWithShadow(textRenderer,"Auto Update",width/2,18,0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,"v" + AutoUpdater.installedVersion(),width/2,128,0xAAAAAA);
    }
    @Override public void close() { client.setScreen(parent); }
}
