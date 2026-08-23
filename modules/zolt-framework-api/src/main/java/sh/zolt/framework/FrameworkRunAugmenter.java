package sh.zolt.framework;

import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A framework adapter is a core build operation, so it receives the authoritative lockfile rather
 * than deriving one (design §4.5). {@link ProjectBuildContext} carries the member's project root, the
 * workspace root's {@code zolt.lock}, and the member path together: an adapter handed only a
 * directory could reach nothing but {@code <member>/zolt.lock}, which no command writes and none may
 * consume, so its plan would change whenever a stray file appeared next to the member's manifest.
 */
@FunctionalInterface
public interface FrameworkRunAugmenter {
    Optional<FrameworkRunResult> augmentIfEnabled(
            ProjectBuildContext context,
            ProjectConfig config,
            Path cacheRoot);

    default boolean isEnabled(ProjectConfig config) {
        return false;
    }

    static FrameworkRunAugmenter none() {
        return (context, config, cacheRoot) -> Optional.empty();
    }
}
