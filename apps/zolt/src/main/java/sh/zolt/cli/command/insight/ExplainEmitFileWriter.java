package sh.zolt.cli.command.insight;

import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.explain.emit.DraftZoltTomlDocument;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Writes the drafted {@code zolt.toml} documents of a migration.
 *
 * <p>{@code --emit-toml-overwrite} can target a live manifest, so replacement goes through a sibling
 * temp file and an atomic rename: a truncating in-place write leaves a half-written manifest behind if
 * anything fails after the truncation. Each target's bytes are captured when the write is planned and
 * re-checked immediately before the rename, so a manifest edited while the migration ran fails the
 * command instead of being silently clobbered.
 */
final class ExplainEmitFileWriter {
    private final CommandSpec spec;
    private final Path outputDirectory;
    private final boolean overwrite;

    ExplainEmitFileWriter(CommandSpec spec, Path outputDirectory, boolean overwrite) {
        this.spec = spec;
        this.outputDirectory = outputDirectory;
        this.overwrite = overwrite;
    }

    void write(Path projectRoot, List<DraftZoltTomlDocument> documents) {
        commit(plan(projectRoot, documents));
    }

    /**
     * Resolves every target and captures the bytes it holds now. Planning is separate from committing
     * because the capture is what makes the commit fail closed: a workspace emit rewrites members one
     * at a time, so the last target is checked against bytes read before the first one was replaced.
     */
    List<EmitWrite> plan(Path projectRoot, List<DraftZoltTomlDocument> documents) {
        Path outputRoot = outputDirectory.isAbsolute()
                ? outputDirectory.toAbsolutePath().normalize()
                : projectRoot.resolve(outputDirectory).toAbsolutePath().normalize();
        try {
            return planWrites(outputRoot, documents);
        } catch (IOException exception) {
            throw CommandFailures.user(
                    spec,
                    "Could not read the existing zolt.toml under " + outputRoot
                            + ". Check that the output directory is readable and rerun.",
                    exception);
        }
    }

    void commit(List<EmitWrite> writes) {
        if (!overwrite) {
            refuseExistingFiles(writes);
        }
        writeFiles(writes);
        printSummary(writes);
    }

    private void refuseExistingFiles(List<EmitWrite> writes) {
        for (EmitWrite write : writes) {
            if (write.captured().isPresent()) {
                throw CommandFailures.user(
                        spec,
                        "Refusing to overwrite zolt.toml at " + write.path()
                                + ". Use --emit-toml-overwrite to replace existing emitted TOML files.",
                        new IOException("Refusing to overwrite " + write.path()));
            }
        }
    }

    private void writeFiles(List<EmitWrite> writes) {
        for (EmitWrite write : writes) {
            try {
                Files.createDirectories(write.path().getParent());
                if (overwrite) {
                    replaceAtomically(write);
                } else {
                    Files.writeString(
                            write.path(),
                            write.contents(),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                }
            } catch (IOException exception) {
                throw CommandFailures.user(
                        spec,
                        "Could not write emitted zolt.toml at " + write.path()
                                + ". Check that the output directory is writable and rerun.",
                        exception);
            }
        }
    }

    private void replaceAtomically(EmitWrite write) throws IOException {
        requireUnchanged(write);
        Path staged = Files.createTempFile(write.path().getParent(), ".zolt-emit-", ".tmp");
        try {
            Files.writeString(staged, write.contents());
            copyPermissions(write.path(), staged);
            requireUnchanged(write);
            move(staged, write.path());
            staged = null;
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // Preserve the write failure; the uniquely named sibling is safe to clean later.
                }
            }
        }
    }

    private void requireUnchanged(EmitWrite write) throws IOException {
        if (contents(write.path()).equals(write.captured())) {
            return;
        }
        throw CommandFailures.user(
                spec,
                "Refusing to overwrite zolt.toml at " + write.path()
                        + " because it changed while the migration was running. Nothing was written to"
                        + " it; rerun the command against the current sources.",
                new IOException("Emit target changed during the run: " + write.path()));
    }

    private static void move(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic replacement of the emitted zolt.toml is not supported at " + target, exception);
        }
    }

    private static void copyPermissions(Path source, Path target) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(source);
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // A missing target or a non-POSIX filesystem keeps the platform defaults.
        }
    }

    private static Optional<String> contents(Path path) throws IOException {
        return Files.exists(path) ? Optional.of(Files.readString(path)) : Optional.empty();
    }

    private List<EmitWrite> planWrites(Path outputRoot, List<DraftZoltTomlDocument> documents) throws IOException {
        List<EmitWrite> writes = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (DraftZoltTomlDocument document : documents) {
            Path relativePath = Path.of(document.relativePath()).normalize();
            Path target = outputRoot.resolve(relativePath).normalize();
            if (relativePath.isAbsolute() || !target.startsWith(outputRoot)) {
                throw CommandFailures.user(
                        spec,
                        "Refusing to write emitted zolt.toml outside output directory: "
                                + document.relativePath()
                                + ". Choose an output directory and member paths that stay under the workspace root.",
                        new IllegalArgumentException("Emitted path escapes output directory: " + document.relativePath()));
            }
            if (!seen.add(target)) {
                throw CommandFailures.user(
                        spec,
                        "Refusing to write duplicate emitted zolt.toml at " + target
                                + ". Check the migrated workspace member paths and rerun.",
                        new IllegalArgumentException("Duplicate emitted path: " + target));
            }
            writes.add(new EmitWrite(target, document.contents(), contents(target)));
        }
        return List.copyOf(writes);
    }

    private void printSummary(List<EmitWrite> writes) {
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        output.summary(
                writes.size() == 1 ? "Wrote draft zolt.toml" : "Wrote draft Zolt workspace",
                writes.size() + (writes.size() == 1 ? " file" : " files"));
        for (EmitWrite write : writes) {
            output.pointer("wrote", write.path().toString());
        }
    }

    /** One planned write, with the target's bytes as they were when the write was planned. */
    record EmitWrite(Path path, String contents, Optional<String> captured) {
    }
}
