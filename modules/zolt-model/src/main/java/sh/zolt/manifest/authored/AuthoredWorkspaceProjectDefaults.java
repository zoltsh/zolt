package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.project.toolchain.JavaFeatureRelease;

/** The closed set of project identity values a workspace may supply to members. */
public record AuthoredWorkspaceProjectDefaults(
        Optional<ProjectGroup> group,
        Optional<ProjectVersion> version,
        Optional<JavaFeatureRelease> javaRelease,
        Optional<ProjectLicense> license) {
    public AuthoredWorkspaceProjectDefaults {
        Objects.requireNonNull(group, "Workspace project group must not be null.");
        Objects.requireNonNull(version, "Workspace project version must not be null.");
        Objects.requireNonNull(javaRelease, "Workspace project Java release must not be null.");
        Objects.requireNonNull(license, "Workspace project license must not be null.");
        if (group.isEmpty() && version.isEmpty() && javaRelease.isEmpty() && license.isEmpty()) {
            throw new IllegalArgumentException("Workspace project defaults must not be empty.");
        }
    }
}
