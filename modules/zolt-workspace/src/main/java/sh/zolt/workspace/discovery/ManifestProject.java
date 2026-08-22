package sh.zolt.workspace.discovery;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.project.ProjectConfig;

/**
 * One project directory evaluated the way design §4.5 "Command discovery" requires: with the
 * workspace root's shared configuration when the directory is a member, and standalone otherwise.
 *
 * <p>{@code workspaceRoot} is present exactly when {@code effective} was composed as a workspace
 * member, so callers can tell a member-directory command from a standalone one without discovering
 * the workspace a second time.
 */
public record ManifestProject(
        Path directory,
        Path manifestPath,
        EffectiveManifest effective,
        ProjectConfig config,
        Optional<Path> workspaceRoot) {
    public ManifestProject {
        directory = Objects.requireNonNull(directory, "Project directory must not be null.");
        manifestPath = Objects.requireNonNull(manifestPath, "Project manifest path must not be null.");
        effective = Objects.requireNonNull(effective, "Effective manifest must not be null.");
        config = Objects.requireNonNull(config, "Project config must not be null.");
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "Workspace root must not be null.");
    }
}
