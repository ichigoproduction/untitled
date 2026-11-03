package untitled.untitled.client;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Separate JVM; installs a version-named JAR only after Minecraft exits. */
public final class UpdateInstaller {
    private UpdateInstaller() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) return;
        long pid = Long.parseLong(args[0]);
        Path original = Path.of(args[1]).toAbsolutePath().normalize();
        Path staged = Path.of(args[2]).toAbsolutePath().normalize();
        String newName = staged.getFileName().toString();

        if (!newName.matches("152-[0-9]+(\\.[0-9]+){1,3}\\.jar")
            || !original.getFileName().toString().endsWith(".jar")
            || !Files.isRegularFile(original) || !Files.isRegularFile(staged)) return;
        // Only allow a standard mods folder or Lunar Client's versioned Fabric mods folder.
        Path parent = original.getParent();
        String folder = parent.getFileName().toString();
        boolean standard = folder.equals("mods");
        boolean lunar = folder.equals("fabric-1.21.4")
            && parent.getParent() != null
            && parent.getParent().getFileName().toString().equals("mods")
            && original.toString().contains(".lunarclient");
        if (!standard && !lunar) {
            System.err.println("Unsupported mod directory: " + parent);
            return;
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            if (System.nanoTime() > deadline) return;
            Thread.sleep(500);
        }

        Path destination = original.resolveSibling(newName);
        if (!destination.equals(original) && Files.exists(destination)) {
            throw new FileAlreadyExistsException("Refusing to overwrite existing update: " + destination);
        }
        Path backup = original.resolveSibling(original.getFileName() + ".152-backup-" + System.currentTimeMillis());
        Path local = Files.createTempFile(original.getParent(), ".152-update-", ".tmp");
        boolean backedUp = false;
        try {
            Files.copy(staged, local, StandardCopyOption.REPLACE_EXISTING);
            // Keep previous version in a non-.jar backup to prevent Fabric loading both.
            Files.move(original, backup);
            backedUp = true;
            try {
                try {
                    Files.move(local, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(local, destination);
                }
            } catch (Exception installError) {
                // Restore the old JAR and its original filename on failure.
                Files.move(backup, original);
                backedUp = false;
                throw installError;
            }
            Files.deleteIfExists(staged);
        } finally {
            Files.deleteIfExists(local);
            // On success keep the backup; on failure keep it if restoration was impossible.
            if (backedUp && !Files.exists(destination)) {
                try {
                    if (!Files.exists(original)) Files.move(backup, original);
                } catch (Exception ignored) { }
            }
        }
    }
}
