package untitled.untitled.client;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Separate JVM; executes only after Minecraft exits and the original JAR is released. */
public final class UpdateInstaller {
    private UpdateInstaller() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 3) return;
        long pid = Long.parseLong(args[0]);
        Path original = Path.of(args[1]).toAbsolutePath().normalize();
        Path staged = Path.of(args[2]).toAbsolutePath().normalize();
        if (!original.getFileName().toString().endsWith(".jar")
                || !staged.getFileName().toString().endsWith(".jar")
                || !Files.isRegularFile(original) || !Files.isRegularFile(staged)) return;
        // Avoid replacing a mod outside its original mods directory.
        if (!original.getParent().getFileName().toString().equals("mods")) return;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() > end) return;
            Thread.sleep(500);
        }
        Path backup = original.resolveSibling(original.getFileName() + ".152-backup");
        if (Files.exists(backup)) return; // Existing backup requires manual resolution.
        Files.copy(original, backup);
        try {
            // Stage alongside the target so the final replacement is on the same filesystem.
            Path local = Files.createTempFile(original.getParent(), ".152-update-", ".jar");
            try {
                Files.copy(staged, local, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(local, original, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(local, original, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(local); }
            Files.deleteIfExists(staged);
        } catch (Exception failure) {
            Files.copy(backup, original, StandardCopyOption.REPLACE_EXISTING);
            throw failure;
        }
    }
}
