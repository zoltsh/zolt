package sh.zolt.build.compile;

import sh.zolt.build.BuildException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resets the two directories owned by a full javac invocation: compiled output and
 * annotation-processor-generated sources.
 *
 * <p>A full compile is a correctness fallback, so reusing either directory would make it observably
 * different from compiling into fresh output. The paths are resolved again through {@link ProjectPaths}
 * before deletion; this keeps cleanup confined to the exact project-relative directories declared by
 * the build instead of trusting an arbitrary caller-provided path.
 */
public final class CompileOutputCleaner {
    private CompileOutputCleaner() {
    }

    public static void resetMain(
            Path projectDirectory,
            ProjectConfig config,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        reset(
                projectDirectory,
                new OwnedDirectory("[build.output].main", config.build().output(), outputDirectory),
                new OwnedDirectory(
                        "[compiler.generated].main",
                        config.compilerSettings().generatedSources(),
                        generatedSourcesDirectory));
    }

    public static void resetTest(
            Path projectDirectory,
            ProjectConfig config,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        reset(
                projectDirectory,
                new OwnedDirectory("[build.output].test", config.build().testOutput(), outputDirectory),
                new OwnedDirectory(
                        "[compiler.generated].test",
                        config.compilerSettings().generatedTestSources(),
                        generatedSourcesDirectory));
    }

    private static void reset(Path projectDirectory, OwnedDirectory... directories) {
        Path projectRoot = ProjectPaths.root(projectDirectory);
        List<Path> owned = new ArrayList<>();
        for (OwnedDirectory directory : directories) {
            Path resolved;
            try {
                resolved = ProjectPaths.output(projectRoot, directory.key(), directory.configuredPath());
            } catch (ProjectPathException exception) {
                throw new BuildException(exception.getMessage(), exception);
            }
            Path requested = directory.requestedPath().toAbsolutePath().normalize();
            if (!resolved.equals(requested)) {
                throw new BuildException(
                        "Refusing to clean compile output " + requested + " because " + directory.key()
                                + " resolves to " + resolved + ".");
            }
            owned.add(resolved);
        }

        // If one owned directory contains another, deleting the parent already resets both. Keeping only
        // the shallow roots also avoids trying to walk a child after its parent has been removed.
        List<Path> roots = owned.stream()
                .distinct()
                .sorted(Comparator.comparingInt(Path::getNameCount))
                .filter(candidate -> owned.stream()
                        .noneMatch(other -> !candidate.equals(other)
                                && other.getNameCount() < candidate.getNameCount()
                                && candidate.startsWith(other)))
                .toList();
        roots.forEach(CompileOutputCleaner::deleteRecursively);
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not reset owned compile output at " + root
                            + ". Check that the build output directory is writable.",
                    exception);
        }
    }

    private record OwnedDirectory(String key, String configuredPath, Path requestedPath) {
    }
}
