package partyhud.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class PartyHudEditor extends Screen {
    public record HudBounds(int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width
                    && mouseY >= y && mouseY < y + height;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("partyhud.json");

    private static boolean initialized = false;
    private static int openDelayTicks = -1;

    private boolean dragging = false;

    private PartyHudEditor() {
        super(Text.literal("Party HUD Editor"));
    }

    public static void register() {
        if (initialized) return;
        initialized = true;

        loadSettings();
        registerEditorCommand("hud");
        registerEditorCommand("partyhud");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openDelayTicks < 0) return;
            if (openDelayTicks > 0) {
                openDelayTicks--;
                return;
            }

            openDelayTicks = -1;
            if (!(client.currentScreen instanceof PartyHudEditor)) {
                client.setScreen(new PartyHudEditor());
            }
        });
    }

    private static void registerEditorCommand(String name) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal(name)
                        .executes(context -> {
                            openDelayTicks = 1;
                            return 1;
                        })
                        .then(literal("reset").executes(context -> {
                            PartyHud.resetPosition();
                            saveSettings();
                            return 1;
                        }))
                )
        );
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x88000000);
        PartyHud.renderEditorPreview(context);

        HudBounds bounds = PartyHud.getEditorBounds();
        drawSelectionBox(context, bounds, dragging || bounds.contains(mouseX, mouseY));

        context.drawCenteredTextWithShadow(
                textRenderer,
                "Drag Party HUD • ESC to save and close",
                width / 2,
                12,
                0xFFFFFFFF
        );

        super.render(context, mouseX, mouseY, delta);
    }

    private static void drawSelectionBox(DrawContext context, HudBounds bounds, boolean active) {
        int color = active ? 0xFFEAD075 : 0x99FFFFFF;
        int x1 = bounds.x() - 4;
        int y1 = bounds.y() - 4;
        int x2 = bounds.x() + bounds.width() + 4;
        int y2 = bounds.y() + bounds.height() + 4;

        context.fill(x1, y1, x2, y1 + 1, color);
        context.fill(x1, y2 - 1, x2, y2, color);
        context.fill(x1, y1, x1 + 1, y2, color);
        context.fill(x2 - 1, y1, x2, y2, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && PartyHud.getEditorBounds().contains(mouseX, mouseY)) {
            dragging = true;
            return true;
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
        if (button == 0 && dragging) {
            PartyHud.moveBy((int) Math.round(deltaX), (int) Math.round(deltaY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
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
        if (!Files.isRegularFile(CONFIG_PATH)) return;

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;
            PartyHud.readSettings(root);
        } catch (Exception ignored) {
        }
    }

    static void saveSettings() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            JsonObject root = new JsonObject();
            PartyHud.writeSettings(root);

            try (Writer writer = Files.newBufferedWriter(
                    CONFIG_PATH,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
        }
    }
}
