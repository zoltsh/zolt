package sh.zolt.sbom;

import java.util.List;
import java.util.Optional;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.ProjectConfig;

/**
 * One policy owner paired with the dependency closure it actually consumes, within the scopes the
 * enforcing command evaluates ({@link SbomScopeSelection#requiredOnly()}) — never the wider set a
 * report may list.
 *
 * <p>A single project is one scope over its own dependencies. A workspace is one scope per member:
 * {@code [dependencies.policy]} is member-local, and a member's policy governs only what that member
 * depends on, so the caller pairs each member's config with that member's projected external closure.
 * Keeping the pairing in the caller is what lets zolt-sbom stay free of any workspace dependency — the
 * projection lives in zolt-workspace and only its result crosses this boundary.
 */
public record LicensePolicyScope(
        ProjectConfig config,
        List<SbomComponent> components,
        Optional<String> member) {
    public LicensePolicyScope(ProjectConfig config, List<SbomComponent> components) {
        this(config, components, Optional.empty());
    }

    public LicensePolicyScope {
        components = List.copyOf(components);
        member = member == null ? Optional.empty() : member;
    }

    /** The owner's configured license policy; {@code isDefault()} means it enforces nothing. */
    public LicensePolicySettings policy() {
        return config.dependencyPolicy().licenses();
    }
}
