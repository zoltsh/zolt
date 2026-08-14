package sh.zolt.build;

import java.nio.file.Path;
import java.util.Objects;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.project.ProjectConfig;

record BuildRequest(
        Path projectDirectory,
        ProjectConfig config,
        Path cacheRoot,
        boolean offline,
        VerifiedArtifactIndex artifactIndex) {
    BuildRequest(Path projectDirectory, ProjectConfig config, Path cacheRoot, boolean offline) {
        this(projectDirectory, config, cacheRoot, offline, new VerifiedArtifactIndex());
    }

    BuildRequest {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(artifactIndex, "artifactIndex");
    }
}
