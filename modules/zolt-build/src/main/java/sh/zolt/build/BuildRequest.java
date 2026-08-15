package sh.zolt.build;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.project.ProjectConfig;

record BuildRequest(
        Path projectDirectory,
        ProjectConfig config,
        Path cacheRoot,
        boolean offline,
        VerifiedArtifactIndex artifactIndex,
        Predicate<ResolvedClasspathPackage> packageFilter) {
    BuildRequest(Path projectDirectory, ProjectConfig config, Path cacheRoot, boolean offline) {
        this(projectDirectory, config, cacheRoot, offline, new VerifiedArtifactIndex(), ignored -> true);
    }

    BuildRequest(
            Path projectDirectory,
            ProjectConfig config,
            Path cacheRoot,
            boolean offline,
            VerifiedArtifactIndex artifactIndex) {
        this(projectDirectory, config, cacheRoot, offline, artifactIndex, ignored -> true);
    }

    BuildRequest {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(artifactIndex, "artifactIndex");
        Objects.requireNonNull(packageFilter, "packageFilter");
    }
}
