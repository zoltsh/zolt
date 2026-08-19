package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.project.toolchain.JavaFeatureRelease;

/** Required and optional project identity after workspace defaults are applied. */
public record EffectiveProjectIdentity(
        EffectiveValue<ProjectName> name,
        EffectiveValue<ProjectVersion> version,
        EffectiveValue<ProjectGroup> group,
        Optional<EffectiveValue<JavaFeatureRelease>> javaRelease,
        Optional<EffectiveValue<ProjectLicense>> license) {
    public EffectiveProjectIdentity {
        name = Objects.requireNonNull(name, "Effective project name must not be null.");
        version = Objects.requireNonNull(version, "Effective project version must not be null.");
        group = Objects.requireNonNull(group, "Effective project group must not be null.");
        javaRelease = Objects.requireNonNull(
                javaRelease, "Effective project Java release must not be null.");
        license = Objects.requireNonNull(license, "Effective project license must not be null.");
        if (name.origin() != ValueOrigin.AUTHORED) {
            throw new IllegalArgumentException("Effective project name must be authored locally.");
        }
        rejectBuiltIn(version, "Effective project version");
        rejectBuiltIn(group, "Effective project group");
        javaRelease.ifPresent(value -> rejectBuiltIn(value, "Effective project Java release"));
        license.ifPresent(value -> rejectBuiltIn(value, "Effective project license"));
    }

    private static void rejectBuiltIn(EffectiveValue<?> value, String label) {
        if (value.origin() == ValueOrigin.BUILT_IN) {
            throw new IllegalArgumentException(label + " must be authored or inherited.");
        }
    }
}
