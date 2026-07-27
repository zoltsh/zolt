package sh.zolt.sbom;

import java.util.List;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.ProjectConfig;

/**
 * One policy owner paired with the dependency closure it actually consumes.
 *
 * <p>A single project is one scope over its own dependencies. A workspace is one scope per member:
 * {@code [dependencyPolicy]} is member-local, and a member's policy governs only what that member
 * depends on, so the caller pairs each member's config with that member's projected external closure.
 * Keeping the pairing in the caller is what lets zolt-sbom stay free of any workspace dependency — the
 * projection lives in zolt-workspace and only its result crosses this boundary.
 */
public record LicensePolicyScope(ProjectConfig config, List<SbomComponent> components) {
    public LicensePolicyScope {
        components = List.copyOf(components);
    }

    /** The owner's configured license policy; {@code isDefault()} means it enforces nothing. */
    public LicensePolicySettings policy() {
        return config.dependencyPolicy().licenses();
    }
}
