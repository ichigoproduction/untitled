package untitled.untitled.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class EditHud extends Screen {
    private enum DragTarget {
        NONE,
        CONTENT,
        PARTY
    }

    public record HudBounds(int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x
                    && mouseX < x + width
                    && mouseY >= y
                    && mouseY < y + height;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("untitled_hud.json");

    private static boolean initialized = false;
    private static int openEditorDelayTicks = -1;

    private DragTarget dragTarget = DragTarget.NONE;

    private EditHud() {
        super(Text.literal("HUD Editor"));
    }

    public static void register() {
        if (initialized) {
            return;
        }
        initialized = true;

        loadSettings();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("hud")
                        .executes(context -> {
                            openEditorDelayTicks = 2;
                            return 1;
                        })
                        .then(literal("reset").executes(context -> {
                            ContentTimer.resetPosition();
                            PartyHud.resetPosition();
                            saveSettings();
                            return 1;
                        }))
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openEditorDelayTicks < 0) {
                return;
            }

            if (openEditorDelayTicks > 0) {
                openEditorDelayTicks--;
                return;
            }

            openEditorDelayTicks = -1;
            if (!(client.currentScreen instanceof EditHud)) {
                client.setScreen(new EditHud());
            }
        });
    }

    @Override
    protected void init() {
        int buttonWidth = 100;
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
                .dimensions((width - buttonWidth) / 2, height - 28, buttonWidth, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x88000000);

        ContentTimer.renderEditorPreview(context);
        PartyHud.renderEditorPreview(context);

        HudBounds contentBounds = ContentTimer.getEditorBounds();
        HudBounds partyBounds = PartyHud.getEditorBounds();

        drawSelectionBox(
                context,
                contentBounds,
                dragTarget == DragTarget.CONTENT || contentBounds.contains(mouseX, mouseY),
                "Power HUD"
        );
        drawSelectionBox(
                context,
                partyBounds,
                dragTarget == DragTarget.PARTY || partyBounds.contains(mouseX, mouseY),
                "Party HUD"
        );

        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("HUD Editor"),
                width / 2,
                12,
                0xFFFFFF
        );
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("Drag a HUD with the left mouse button · ESC or Done to save"),
                width / 2,
                26,
                0xBFC7D5
        );
        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("/hud reset restores both default positions"),
                width / 2,
                38,
                0x8F9AAA
        );

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawSelectionBox(
            DrawContext context,
            HudBounds bounds,
            boolean active,
            String label
    ) {
        int color = active ? 0xFFEAD075 : 0x99FFFFFF;
        int x1 = bounds.x() - 4;
        int y1 = bounds.y() - 4;
        int x2 = bounds.x() + bounds.width() + 4;
        int y2 = bounds.y() + bounds.height() + 4;

        context.fill(x1, y1, x2, y1 + 1, color);
        context.fill(x1, y2 - 1, x2, y2, color);
        context.fill(x1, y1, x1 + 1, y2, color);
        context.fill(x2 - 1, y1, x2, y2, color);
        context.drawTextWithShadow(
                textRenderer,
                Text.literal(label),
                x1,
                Math.max(2, y1 - textRenderer.fontHeight - 2),
                color
        );
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (ContentTimer.getEditorBounds().contains(mouseX, mouseY)) {
                dragTarget = DragTarget.CONTENT;
                return true;
            }
            if (PartyHud.getEditorBounds().contains(mouseX, mouseY)) {
                dragTarget = DragTarget.PARTY;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(
            double mouseX,
            double mouseY,
            int button,
            double deltaX,
            double deltaY
    ) {
        if (button == 0 && dragTarget != DragTarget.NONE) {
            int moveX = (int) Math.round(deltaX);
            int moveY = (int) Math.round(deltaY);

            if (dragTarget == DragTarget.CONTENT) {
                ContentTimer.moveBy(moveX, moveY);
            } else if (dragTarget == DragTarget.PARTY) {
                PartyHud.moveBy(moveX, moveY);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragTarget != DragTarget.NONE) {
            dragTarget = DragTarget.NONE;
            saveSettings();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        saveSettings();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static void loadSettings() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }

            int contentX = root.has("contentOffsetX")
                    ? root.get("contentOffsetX").getAsInt()
                    : 0;
            int contentY = root.has("contentOffsetY")
                    ? root.get("contentOffsetY").getAsInt()
                    : 0;
            int partyX = root.has("partyOffsetX")
                    ? root.get("partyOffsetX").getAsInt()
                    : 0;
            int partyY = root.has("partyOffsetY")
                    ? root.get("partyOffsetY").getAsInt()
                    : 0;

            ContentTimer.setOffsets(contentX, contentY);
            PartyHud.setOffsets(partyX, partyY);
            PartyHud.readSettings(root);
        } catch (Exception ignored) {
        }
    }

    static void saveSettings() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            JsonObject root = new JsonObject();
            root.addProperty("contentOffsetX", ContentTimer.getOffsetX());
            root.addProperty("contentOffsetY", ContentTimer.getOffsetY());
            root.addProperty("partyOffsetX", PartyHud.getOffsetX());
            root.addProperty("partyOffsetY", PartyHud.getOffsetY());
            PartyHud.writeSettings(root);

            try (Writer writer = Files.newBufferedWriter(
                    CONFIG_PATH,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
        }
    }
}
