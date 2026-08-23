package sh.zolt.build;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.ProjectConfig;

/**
 * One resolve-and-build request. Carries the {@link ProjectBuildContext} rather than a bare project
 * directory so the lane that resolves the lock and the lane that fingerprints the compile agree on
 * which {@code zolt.lock} is authoritative (design §4.5).
 */
record BuildRequest(
        ProjectBuildContext context,
        ProjectConfig config,
        Path cacheRoot,
        boolean offline,
        VerifiedArtifactIndex artifactIndex,
        Predicate<ResolvedClasspathPackage> packageFilter) {
    BuildRequest(Path projectDirectory, ProjectConfig config, Path cacheRoot, boolean offline) {
        this(ProjectBuildContext.standalone(projectDirectory), config, cacheRoot, offline);
    }

    BuildRequest(ProjectBuildContext context, ProjectConfig config, Path cacheRoot, boolean offline) {
        this(context, config, cacheRoot, offline, new VerifiedArtifactIndex(), ignored -> true);
    }

    BuildRequest(
            ProjectBuildContext context,
            ProjectConfig config,
            Path cacheRoot,
            boolean offline,
            VerifiedArtifactIndex artifactIndex) {
        this(context, config, cacheRoot, offline, artifactIndex, ignored -> true);
    }

    BuildRequest {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(artifactIndex, "artifactIndex");
        Objects.requireNonNull(packageFilter, "packageFilter");
    }

    /** Where this project's manifest, sources, and outputs live. */
    Path projectDirectory() {
        return context.projectRoot();
    }

    /** The authoritative lockfile for this build — the workspace root's for a member. */
    Path lockfilePath() {
        return context.lockfilePath();
    }
}
