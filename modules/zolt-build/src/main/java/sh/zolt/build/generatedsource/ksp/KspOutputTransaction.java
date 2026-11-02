package sh.zolt.build.generatedsource.ksp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import sh.zolt.build.BuildException;
import sh.zolt.project.GeneratedSourceStep;

/** Stages one KSP run beside its live output and publishes it only after successful execution. */
final class KspOutputTransaction implements AutoCloseable {
    private static final String STAGING_MARKER = ".zolt-ksp-staging-";
    private static final String BACKUP_MARKER = ".zolt-ksp-old-";

    private final KspOutputLayout live;
    private final KspOutputLayout staging;
    private boolean committed;

    private KspOutputTransaction(KspOutputLayout live, KspOutputLayout staging) {
        this.live = live;
        this.staging = staging;
    }

    static KspOutputTransaction begin(
            Path projectRoot,
            String scope,
            GeneratedSourceStep step) {
        KspOutputLayout live = KspGeneratedSourceValidator.validate(projectRoot, scope, step);
        Path parent = live.baseDirectory().getParent();
        if (parent == null) {
            throw new BuildException("KSP output cannot be a filesystem root.");
        }
        Path stagingBase = parent.resolve(
                "." + live.baseDirectory().getFileName() + STAGING_MARKER + UUID.randomUUID());
        KspOutputLayout staging = layout(stagingBase);
        try {
            Files.createDirectories(staging.cachesDirectory());
            Files.createDirectories(staging.classOutputDirectory());
            Files.createDirectories(staging.kotlinOutputDirectory());
            Files.createDirectories(staging.javaOutputDirectory());
            Files.createDirectories(staging.resourceOutputDirectory());
        } catch (IOException exception) {
            deleteQuietly(stagingBase);
            throw new BuildException(
                    "Could not create staged KSP output beside " + live.baseDirectory()
                            + ". Check that the output directory is writable.",
                    exception);
        }
        return new KspOutputTransaction(live, staging);
    }

    KspOutputLayout staging() {
        return staging;
    }

    KspOutputLayout live() {
        return live;
    }

    void commit() {
        if (committed) {
            throw new IllegalStateException("KSP output transaction is already committed.");
        }
        Path target = live.baseDirectory();
        Path backup = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                backup = target.resolveSibling(
                        "." + target.getFileName() + BACKUP_MARKER + UUID.randomUUID());
                Files.move(target, backup);
            }
            try {
                Files.move(staging.baseDirectory(), target);
            } catch (IOException exception) {
                restoreBackup(backup, target, exception);
                throw exception;
            }
            committed = true;
            deleteQuietly(backup);
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not publish staged KSP output at " + target
                            + ". The previous generated output was preserved when possible.",
                    exception);
        }
    }

    @Override
    public void close() {
        if (!committed) {
            deleteQuietly(staging.baseDirectory());
        }
    }

    private static KspOutputLayout layout(Path base) {
        return new KspOutputLayout(
                base,
                base.resolve("cache"),
                base.resolve("classes"),
                base.resolve("kotlin"),
                base.resolve("java"),
                base.resolve("resources"));
    }

    private static void restoreBackup(
            Path backup,
            Path target,
            IOException original) {
        if (backup == null) {
            return;
        }
        try {
            Files.move(backup, target);
        } catch (IOException rollback) {
            original.addSuppressed(rollback);
        }
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A hidden staging or backup tree is a disk cost, never a correctness input.
                }
            }
        } catch (IOException ignored) {
            // Best effort for the same reason.
        }
    }
}
