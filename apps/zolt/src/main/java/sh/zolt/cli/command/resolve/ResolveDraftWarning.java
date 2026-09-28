package sh.zolt.cli.command.resolve;

import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Detects when a {@code zolt resolve} finished on a draft whose dependencies were demoted to
 * emit review-item comments and therefore resolved into a near-empty graph.
 *
 * <p>The signal is either the source {@code zolt.toml} still carrying the {@code # Review items:}
 * markers that {@code zolt explain --emit-toml} writes for ambiguous facts it could not map, or a
 * zero-package graph on a project that nevertheless declares source files to build. In both cases
 * resolve should warn rather than green-light an unqualified {@code Next: zolt build} hand-off.
 */
final class ResolveDraftWarning {
    /** Marker emit writes above the review-item comment block; the parser discards comments. */
    private static final String REVIEW_ITEMS_MARKER = "# Review items:";

    private ResolveDraftWarning() {
    }

    /**
     * Returns the warning to surface for a resolve that near-empties an emit draft, or empty when the
     * graph is a genuine, fully-resolved (or legitimately dependency-free) result.
     */
    static Optional<String> forResult(Path projectRoot, ProjectConfig config, ResolveResult result) {
        if (result.resolvedCount() > 0) {
            return Optional.empty();
        }
        boolean reviewItems = hasReviewItemMarkers(projectRoot.resolve("zolt.toml"));
        boolean declaredSources = hasDeclaredSources(projectRoot, config);
        if (!reviewItems && !declaredSources) {
            return Optional.empty();
        }
        return Optional.of(
                "The source build's dependencies are unversioned or commented and were not resolved; "
                        + "the resolved graph is near-empty. Review the `# Review items:` comments in "
                        + "zolt.toml and add the missing versions before building.");
    }

    private static boolean hasReviewItemMarkers(Path tomlPath) {
        if (!Files.isRegularFile(tomlPath)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(tomlPath);
            for (String line : lines) {
                if (line.strip().startsWith(REVIEW_ITEMS_MARKER)) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static boolean hasDeclaredSources(Path projectRoot, ProjectConfig config) {
        for (String sourceRoot : config.build().sourceRoots()) {
            if (sourceRoot == null || sourceRoot.isBlank()) {
                continue;
            }
            Path root = projectRoot.resolve(sourceRoot);
            if (containsCompilableSource(root)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCompilableSource(Path root) {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(path -> {
                if (!Files.isRegularFile(path)) {
                    return false;
                }
                String name = path.getFileName().toString();
                return name.endsWith(".java")
                        || name.endsWith(".groovy")
                        || name.endsWith(".kt");
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
