package untitled.untitled.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class Optimizer {
    // Ported from ichigoproduction/152, with one independent /optimizer toggle.
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir()
            .resolve("152_optimizer.json");
    private static boolean enabled = false;
    private static boolean initialized = false;

    private static final float FIRE_OVERLAY_Y_MULTIPLIER = 2.0F;
    private static final float WATER_OVERLAY_ALPHA = 0.0F;
    private static final float POWDER_SNOW_OVERLAY_ALPHA = 0.20F;
    private static final float PORTAL_OVERLAY_ALPHA = 0.20F;

    private Optimizer() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        loadSettings();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("optimizer")
                        .then(literal("toggle").executes(context -> {
                            enabled = !enabled;
                            saveSettings();
                            context.getSource().sendFeedback(
                                    Text.literal("optimizer → " + (enabled ? "ON" : "OFF")));
                            return 1;
                        })))
        );
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static void loadSettings() {
        enabled = false;
        if (!Files.isRegularFile(CONFIG)) return;
        try (Reader reader = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
            JsonObject data = JsonParser.parseReader(reader).getAsJsonObject();
            if (data.has("enabled")) enabled = data.get("enabled").getAsBoolean();
        } catch (Exception ignored) {
            enabled = false;
        }
    }

    private static void saveSettings() {
        try {
            Files.createDirectories(CONFIG.getParent());
            JsonObject data = new JsonObject();
            data.addProperty("enabled", enabled);
            Files.writeString(CONFIG, data.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception e) {
            // Toggle remains active for this session even if persistence fails.
        }
    }

    public static boolean disableShadows() {
        return isEnabled();
    }

    public static boolean disableBreakParticles() {
        return isEnabled();
    }

    public static boolean disableFog() {
        return isEnabled();
    }

    public static boolean antiNauseaWobble() {
        return isEnabled();
    }

    public static boolean antiPumpkinOverlay() {
        return isEnabled();
    }

    public static boolean antiFireOverlay() {
        return isEnabled();
    }

    public static boolean antiPortalOverlay() {
        return isEnabled();
    }

    public static boolean portalMenu() {
        return isEnabled();
    }

    public static boolean transparentLoadingTerrain() {
        return isEnabled();
    }

    public static boolean softenWaterOverlay() {
        return isEnabled();
    }

    public static boolean softenLavaVision() {
        return isEnabled();
    }

    public static boolean softenPowderSnowOverlay() {
        return isEnabled();
    }

    public static boolean removeBlindnessDarknessFog() {
        return isEnabled();
    }

    public static boolean removeBlindnessFog() {
        return isEnabled();
    }

    public static boolean removeDarknessFog() {
        return isEnabled();
    }

    public static boolean removeDarknessPulse() {
        return isEnabled();
    }

    public static boolean noVignette() {
        return isEnabled();
    }

    public static boolean noWeatherParticles() {
        return isEnabled();
    }

    public static float getFireOverlayYMultiplier() {
        return FIRE_OVERLAY_Y_MULTIPLIER;
    }

    public static float getWaterOverlayAlpha() {
        return WATER_OVERLAY_ALPHA;
    }

    public static float getPowderSnowOverlayAlpha() {
        return POWDER_SNOW_OVERLAY_ALPHA;
    }

    public static float getPortalOverlayAlpha() {
        return PORTAL_OVERLAY_ALPHA;
    }
}