package untitled.untitled.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class CameraControl {
    private static final float DEFAULT_DISTANCE = 4.0F;
    private static final boolean DEFAULT_NOCLIP = true;
    private static final float MIN_DISTANCE = 0.0F;
    private static final float MAX_DISTANCE = 10.0F;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("untitled_camera.json");

    private static boolean initialized = false;
    private static float cameraDistance = DEFAULT_DISTANCE;
    private static boolean cameraNoclip = DEFAULT_NOCLIP;

    private CameraControl() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        loadSettings();
        registerCommands();
    }

    public static float getCameraDistance() {
        return cameraDistance;
    }

    public static boolean isCameraNoclipEnabled() {
        return cameraNoclip;
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(literal("camera")
                        .then(literal("distance")
                                .then(argument("value", FloatArgumentType.floatArg(
                                                MIN_DISTANCE,
                                                MAX_DISTANCE
                                        ))
                                        .executes(context -> {
                                            cameraDistance = FloatArgumentType.getFloat(context, "value");
                                            saveSettings();
                                            return 1;
                                        })))
                        .then(literal("noclip")
                                .then(literal("on").executes(context -> {
                                    cameraNoclip = true;
                                    saveSettings();
                                    return 1;
                                }))
                                .then(literal("off").executes(context -> {
                                    cameraNoclip = false;
                                    saveSettings();
                                    return 1;
                                })))
                )
        );
    }

    private static void loadSettings() {
        cameraDistance = DEFAULT_DISTANCE;
        cameraNoclip = DEFAULT_NOCLIP;

        if (!Files.isRegularFile(CONFIG_PATH)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }

            if (root.has("cameraDistance")) {
                cameraDistance = clamp(root.get("cameraDistance").getAsFloat());
            }
            if (root.has("cameraNoclip")) {
                cameraNoclip = root.get("cameraNoclip").getAsBoolean();
            }
        } catch (Exception ignored) {
            cameraDistance = DEFAULT_DISTANCE;
            cameraNoclip = DEFAULT_NOCLIP;
        }
    }

    private static void saveSettings() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            JsonObject root = new JsonObject();
            root.addProperty("cameraDistance", cameraDistance);
            root.addProperty("cameraNoclip", cameraNoclip);

            try (Writer writer = Files.newBufferedWriter(
                    CONFIG_PATH,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                GSON.toJson(root, writer);
            }
        } catch (Exception ignored) {
        }
    }

    private static float clamp(float value) {
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, value));
    }
}
