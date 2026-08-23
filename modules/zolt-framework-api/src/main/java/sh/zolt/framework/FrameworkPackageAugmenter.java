package sh.zolt.framework;

import sh.zolt.project.PackageMode;
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
public interface FrameworkPackageAugmenter {
    Optional<FrameworkPackageResult> augmentIfEnabled(
            ProjectBuildContext context,
            ProjectConfig config,
            Path cacheRoot);

    default String missingPackageResultMessage(PackageMode mode) {
        return "Framework package mode `"
                + mode.configValue()
                + "` requires a matching framework adapter. Enable the framework in zolt.toml, run `zolt resolve`, then retry.";
    }

    default String missingRunnerJarMessage(PackageMode mode, Path runnerJar) {
        return "Framework package mode `"
                + mode.configValue()
                + "` expected a runner jar at "
                + runnerJar
                + ". Run `zolt build` and check the framework package output.";
    }

    default String inspectPackageDirectoryMessage(PackageMode mode, Path packageDirectory) {
        return "Could not inspect framework package directory at "
                + packageDirectory
                + ". Check that the package output is readable and retry.";
    }

    static FrameworkPackageAugmenter none() {
        return (context, config, cacheRoot) -> Optional.empty();
    }
}
