package untitled.untitled.client;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class EditHud extends Screen {
    private enum DragTarget {
        NONE,
        PARTY,
        FOOD,
        DRINK,
        SYRINGE,
        SKILL
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = LoggerFactory.getLogger("untitled/EditHud");
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("152_hud.json");

    private static boolean initialized = false;
    private static boolean loadingSettings = false;
    private static int openEditorDelayTicks = -1;
    private DragTarget dragTarget = DragTarget.NONE;

    public record HudBounds(int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x
                    && mouseX < x + width
                    && mouseY >= y
                    && mouseY < y + height;
        }
    }

    private EditHud() {
        super(Text.empty());
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
                            PartyHud.resetPosition();
                            FoodStack.resetPositions();
                            SyringeCooldownHud.resetPosition();
                            SkillHud.resetPosition();
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0x88000000);

        PartyHud.renderEditorPreview(context);
        FoodStack.renderFoodEditorPreview(context);
        FoodStack.renderDrinkEditorPreview(context);
        SyringeCooldownHud.renderEditorPreview(context);
        SkillHud.renderEditorPreview(context);

        HudBounds partyBounds = PartyHud.getEditorBounds();
        HudBounds foodBounds = FoodStack.getFoodEditorBounds();
        HudBounds drinkBounds = FoodStack.getDrinkEditorBounds();
        HudBounds syringeBounds = SyringeCooldownHud.getEditorBounds();
        HudBounds skillBounds = SkillHud.getEditorBounds();

        drawSelectionBox(
                context,
                partyBounds,
                dragTarget == DragTarget.PARTY || partyBounds.contains(mouseX, mouseY)
        );
        drawSelectionBox(
                context,
                foodBounds,
                dragTarget == DragTarget.FOOD || foodBounds.contains(mouseX, mouseY)
        );
        drawSelectionBox(
                context,
                drinkBounds,
                dragTarget == DragTarget.DRINK || drinkBounds.contains(mouseX, mouseY)
        );
        drawSelectionBox(
                context,
                syringeBounds,
                dragTarget == DragTarget.SYRINGE || syringeBounds.contains(mouseX, mouseY)
        );
        drawSelectionBox(
                context,
                skillBounds,
                dragTarget == DragTarget.SKILL || skillBounds.contains(mouseX, mouseY)
        );

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawSelectionBox(DrawContext context, HudBounds bounds, boolean active) {
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
        if (button == 0) {
            if (SkillHud.getEditorBounds().contains(mouseX, mouseY)) {
                dragTarget = DragTarget.SKILL;
                return true;
            }
            // The syringe preview is drawn on top, so it gets first chance at a click.
            if (SyringeCooldownHud.getEditorBounds().contains(mouseX, mouseY)) {
                dragTarget = DragTarget.SYRINGE;
                return true;
            }
            if (PartyHud.getEditorBounds().contains(mouseX, mouseY)) {
                dragTarget = DragTarget.PARTY;
                return true;
            }
            if (FoodStack.getFoodEditorBounds().contains(mouseX, mouseY)) {
                dragTarget = DragTarget.FOOD;
                return true;
            }
            if (FoodStack.getDrinkEditorBounds().contains(mouseX, mouseY)) {
                dragTarget = DragTarget.DRINK;
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

            if (dragTarget == DragTarget.PARTY) {
                PartyHud.moveBy(moveX, moveY);
            } else if (dragTarget == DragTarget.FOOD) {
                FoodStack.moveFoodBy(moveX, moveY);
            } else if (dragTarget == DragTarget.DRINK) {
                FoodStack.moveDrinkBy(moveX, moveY);
            } else if (dragTarget == DragTarget.SYRINGE) {
                SyringeCooldownHud.moveBy(moveX, moveY);
            } else if (dragTarget == DragTarget.SKILL) {
                SkillHud.moveBy(moveX, moveY);
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

        loadingSettings = true;
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                LOG.warn("HUD settings file is empty: {}", CONFIG_PATH);
                return;
            }

            // Restore skill settings independently. Previously an error while
            // loading party, food or syringe settings prevented this call
            // and silently reset skill selections on next save.
            readSection("skill", () -> SkillHud.readSettings(root));
            readSection("party position", () -> {
                int partyX = root.has("partyOffsetX")
                        ? root.get("partyOffsetX").getAsInt()
                        : 0;
                int partyY = root.has("partyOffsetY")
                        ? root.get("partyOffsetY").getAsInt()
                        : 0;
                PartyHud.setOffsets(partyX, partyY);
            });
            readSection("party", () -> PartyHud.readSettings(root));
            readSection("food", () -> FoodStack.readSettings(root));
            readSection("syringe", () -> SyringeCooldownHud.readSettings(root));
        } catch (Exception e) {
            LOG.warn("Failed to read HUD settings from {}", CONFIG_PATH, e);
        } finally {
            loadingSettings = false;
        }
    }

    private static void readSection(String section, Runnable apply) {
        try {
            apply.run();
        } catch (RuntimeException error) {
            LOG.warn("Invalid {} HUD settings in {}; other HUD settings will still load",
                    section, CONFIG_PATH, error);
        }
    }

    /**
     * Use a complete temporary JSON file plus an atomic replace when the
     * file system supports it. A crash mid-save must not leave a truncated
     * config that resets every HUD at the next launch.
     */
    static synchronized void saveSettings() {
        if (loadingSettings) {
            return;
        }
        Path temporary = null;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            JsonObject root = new JsonObject();
            root.addProperty("partyOffsetX", PartyHud.getOffsetX());
            root.addProperty("partyOffsetY", PartyHud.getOffsetY());
            PartyHud.writeSettings(root);
            FoodStack.writeSettings(root);
            SyringeCooldownHud.writeSettings(root);
            SkillHud.writeSettings(root);

            temporary = Files.createTempFile(
                    CONFIG_PATH.getParent(), "152_hud-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
            )) {
                GSON.toJson(root, writer);
            }

            try {
                Files.move(temporary, CONFIG_PATH,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.warn("Failed to save HUD settings to {}", CONFIG_PATH, e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    LOG.warn("Cannot remove temporary HUD settings file {}", temporary, e);
                }
            }
        }
    }
}
