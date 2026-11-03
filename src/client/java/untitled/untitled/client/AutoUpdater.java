package untitled.untitled.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.zip.ZipFile;

/** Updates are opt-in; download never modifies a running mod. */
public final class AutoUpdater {
    private static final URI MANIFEST = URI.create("https://152-updates.torclassic01.workers.dev/latest.json");
    private static final String HOST = MANIFEST.getHost();
    private static final long LIMIT = 25L * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Path STAGING = FabricLoader.getInstance().getGameDir().resolve("config").resolve("152-update");
    private static volatile String status = "Not checked";
    private static volatile Release release;
    private static volatile Path prepared;
    private static volatile boolean busy;
    private static boolean hookRegistered;

    private record Release(String version, String minecraft, URI download, String sha256, long size) {}
    private AutoUpdater() {}

    public static String status() { return status; }
    public static String installedVersion() { return currentVersion(); }
    public static String availableVersion() { return release == null ? "" : release.version(); }
    public static boolean hasUpdate() { return release != null; }
    public static boolean isBusy() { return busy; }
    public static boolean isPrepared() { return prepared != null; }

    private static void ui(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> status = message);
    }

    private static String currentVersion() {
        return FabricLoader.getInstance().getModContainer("untitled").orElseThrow().getMetadata().getVersion().getFriendlyString();
    }

    private static int compare(String a, String b) {
        String[] left = a.split("\\."), right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? Integer.parseInt(left[i]) : 0;
            int r = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }

    public static void check() {
        if (busy) return;
        busy = true;
        ui("Checking updates...");
        Thread.ofVirtual().start(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(MANIFEST).timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200 || response.body().length() > 8192) throw new IllegalStateException("Invalid manifest response");
                JsonObject o = JsonParser.parseString(response.body()).getAsJsonObject();
                String version = o.get("version").getAsString();
                String minecraft = o.get("minecraft").getAsString();
                String hash = o.get("sha256").getAsString().toLowerCase(java.util.Locale.ROOT);
                URI uri = URI.create(o.get("download").getAsString());
                long size = o.get("size").getAsLong();
                if (!version.matches("[0-9]+(\\.[0-9]+){1,3}") || !minecraft.equals("1.21.4")
                    || !hash.matches("[0-9a-f]{64}") || size < 1 || size > LIMIT
                    || !uri.getScheme().equals("https") || !HOST.equals(uri.getHost())
                    || uri.getRawQuery() != null || !uri.getPath().equals("/downloads/152-" + version + ".jar"))
                    throw new IllegalArgumentException("Untrusted update metadata");
                if (compare(version, currentVersion()) > 0) {
                    release = new Release(version,minecraft,uri,hash,size);
                    ui("Version " + version + " available - approval required");
                } else {
                    release = null;
                    ui("Up to date (" + currentVersion() + ")");
                }
            } catch (Exception e) { ui("Update check failed: " + e.getClass().getSimpleName()); }
            finally { busy = false; }
        });
    }

    public static void approveDownload() {
        Release selected = release;
        if (selected == null || busy) return;
        busy = true;
        ui("Downloading update...");
        Thread.ofVirtual().start(() -> {
            Path temp = null;
            try {
                Files.createDirectories(STAGING);
                temp = Files.createTempFile(STAGING, "download-", ".jar");
                HttpRequest request = HttpRequest.newBuilder(selected.download()).timeout(Duration.ofSeconds(60)).GET().build();
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) throw new IllegalStateException("Download HTTP " + response.statusCode());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (InputStream in = response.body(); var out = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n=in.read(buffer))!=-1) {
                        total += n;
                        if (total > LIMIT || total > selected.size()) throw new IllegalArgumentException("Oversized JAR");
                        digest.update(buffer,0,n); out.write(buffer,0,n);
                    }
                }
                if (total != selected.size() || !HexFormat.of().formatHex(digest.digest()).equals(selected.sha256()))
                    throw new IllegalArgumentException("SHA-256 mismatch");
                try (ZipFile zip = new ZipFile(temp.toFile())) {
                    var entry = zip.getEntry("fabric.mod.json");
                    if (entry == null || zip.getEntry("untitled/untitled/client/UntitledClient.class") == null)
                        throw new IllegalArgumentException("Invalid Fabric JAR");
                    JsonObject metadata;
                    try (InputStream in = zip.getInputStream(entry)) {
                        metadata = JsonParser.parseString(new String(in.readNBytes(32768), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                    }
                    if (!"untitled".equals(metadata.get("id").getAsString())
                        || !selected.version().equals(metadata.get("version").getAsString()))
                        throw new IllegalArgumentException("Wrong mod/version");
                }
                Path target = STAGING.resolve("152-" + selected.version() + ".jar");
                Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
                temp = null;
                prepared = target;
                installOnShutdown();
                ui("Verified. Close Minecraft to install; restart afterward.");
            } catch(Exception e) { ui("Update failed: " + e.getClass().getSimpleName()); }
            finally { if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){} busy=false; }
        });
    }

    private static synchronized void installOnShutdown() {
        if (hookRegistered) return;
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Path staged = prepared;
                if (staged == null || !Files.isRegularFile(staged)) return;
                var paths = FabricLoader.getInstance().getModContainer("untitled").orElseThrow().getOrigin().getPaths();
                if (paths.size()!=1) return;
                Path old = paths.get(0).toAbsolutePath().normalize();
                if (!Files.isRegularFile(old) || !old.toString().endsWith(".jar")) return;
                Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toAbsolutePath();
                if (!Files.isRegularFile(javaExecutable)) return;
                new ProcessBuilder(javaExecutable.toString(), "-cp", old.toString(), "untitled.untitled.client.UpdateInstaller",
                    Long.toString(ProcessHandle.current().pid()), old.toString(), staged.toString())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(STAGING.resolve("install.log").toFile()))
                    .redirectErrorStream(true).start();
            } catch(Exception e) {
                try {
                    Files.createDirectories(STAGING);
                    Files.writeString(STAGING.resolve("install.log"), "Shutdown helper launch failed: " + e + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (Exception ignored) { }
            }
        }, "152-update-shutdown"));
    }
}
