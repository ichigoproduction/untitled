package untitled.untitled.client;

import java.nio.file.*;
import java.io.IOException;
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
        System.out.println("Waiting for file access: " + original);
        Path local = Files.createTempFile(original.getParent(), ".152-update-", ".tmp");
        boolean backedUp = false;
        try {
            Files.copy(staged, local, StandardCopyOption.REPLACE_EXISTING);
            // Keep a temporary non-.jar backup for rollback during the install.
            moveWhenUnlocked(original, backup);
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
            // The update is installed; do not retain a permanent backup in mods.
            try {
                Files.deleteIfExists(backup);
                backedUp = false;
                System.out.println("Update installed; temporary backup removed: " + destination);
            } catch (IOException cleanupError) {
                System.err.println("Update installed, but could not remove temporary backup: " + cleanupError);
            }
        } finally {
            Files.deleteIfExists(local);
            // If installation failed and rollback could not complete, preserve the backup for recovery.
            if (backedUp && !Files.exists(destination)) {
                try {
                    if (!Files.exists(original)) Files.move(backup, original);
                } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Windows launchers can retain a JAR handle after the Minecraft JVM exits.
     * Retry only sharing/IO failures; never overwrite a destination or bypass locks.
     */
    private static void moveWhenUnlocked(Path original, Path backup) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        int attempts = 0;
        while (true) {
            if (Files.exists(backup)) throw new FileAlreadyExistsException(backup.toString());
            try {
                Files.move(original, backup);
                System.out.println("Backup created after " + attempts + " retries: " + backup);
                return;
            } catch (FileSystemException e) {
                if (System.nanoTime() >= deadline) {
                    System.err.println("Timed out waiting for JAR unlock: " + original);
                    throw e;
                }
                if (attempts++ % 10 == 0) System.out.println("JAR still locked, retrying: " + e.getMessage());
                Thread.sleep(1000);
            }
        }
    }
}
