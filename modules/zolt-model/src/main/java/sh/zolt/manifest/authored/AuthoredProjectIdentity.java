package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ProjectGroup;
import sh.zolt.manifest.ProjectLicense;
import sh.zolt.manifest.ProjectName;
import sh.zolt.manifest.ProjectVersion;
import sh.zolt.project.toolchain.JavaFeatureRelease;

/** Authored project identity before workspace defaults are applied. */
public record AuthoredProjectIdentity(
        ProjectName name,
        Optional<ProjectVersion> version,
        Optional<ProjectGroup> group,
        Optional<JavaFeatureRelease> javaRelease,
        Optional<ProjectLicense> license) {
    public AuthoredProjectIdentity {
        Objects.requireNonNull(name, "Project name must not be null.");
        Objects.requireNonNull(version, "Project version must not be null.");
        Objects.requireNonNull(group, "Project group must not be null.");
        Objects.requireNonNull(javaRelease, "Project Java release must not be null.");
        Objects.requireNonNull(license, "Project license must not be null.");
    }
}
