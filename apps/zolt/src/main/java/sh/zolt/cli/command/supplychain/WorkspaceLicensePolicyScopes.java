package sh.zolt.cli.command.supplychain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.sbom.LicensePolicyScope;
import sh.zolt.sbom.SbomComponent;
import sh.zolt.sbom.SbomScopeGroup;
import sh.zolt.sbom.SbomScopeSelection;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;

/**
 * Pairs each workspace member's license policy with the dependencies that member actually consumes, so
 * {@code zolt licenses --workspace} annotates the way {@code zolt check --workspace --check
 * license-policy} enforces.
 *
 * <p>{@code [dependencies.policy]} is member-local. Evaluating every member's policy against the whole
 * aggregate would let a strict member deny a coordinate it never depends on, and the report would then
 * contradict the very command it names as the enforcer.
 *
 * <p>The closure comes from {@link WorkspaceMemberSbomLockProjection}, the same projection the workspace
 * quality check consumes. Holding this in the CLI — which already sees both zolt-workspace and
 * zolt-sbom — is what keeps zolt-sbom free of any workspace dependency: only the projected result
 * crosses the boundary, as a plain list of components.
 *
 * <p>The projection is filtered at {@link SbomScopeSelection#requiredOnly()} whatever scopes the report
 * was asked for, because that is what {@code zolt check --workspace --check license-policy} evaluates.
 * A member's test-only dependency is therefore listed by a {@code --include-test} report and left
 * unannotated rather than marked against a policy the named command never applies to it.
 */
final class WorkspaceLicensePolicyScopes {
    private final WorkspaceMemberPolicyResolver policyResolver = new WorkspaceMemberPolicyResolver();
    private final WorkspaceMemberSbomLockProjection projection = new WorkspaceMemberSbomLockProjection();

    /**
     * One scope per member that configures a policy. Members without one are skipped rather than
     * projected: they would contribute nothing, and the projection is not free.
     *
     * @param components the enforcing-scope components the report resolved, already narrowed to
     *     {@link SbomScopeSelection#requiredOnly()} by the caller
     */
    List<LicensePolicyScope> from(
            Workspace workspace,
            ZoltLockfile lockfile,
            List<SbomComponent> components) {
        SbomScopeSelection enforced = SbomScopeSelection.requiredOnly();
        List<LicensePolicyScope> scopes = new ArrayList<>();
        for (WorkspaceMember member : workspace.members()) {
            ProjectConfig effectiveConfig = policyResolver.merge(workspace, member);
            if (effectiveConfig.dependencyPolicy().licenses().isDefault()) {
                continue;
            }
            ZoltLockfile memberLock =
                    projection.project(member.path(), effectiveConfig, lockfile, workspace, policyResolver);
            Set<String> consumed = consumedCoordinates(memberLock, enforced);
            scopes.add(new LicensePolicyScope(
                    effectiveConfig,
                    components.stream()
                            .filter(component -> consumed.contains(coordinate(component)))
                            .toList(),
                    Optional.of(member.path())));
        }
        return List.copyOf(scopes);
    }

    /** External coordinates in the member's projected closure; first-party members are not third parties. */
    private static Set<String> consumedCoordinates(ZoltLockfile memberLock, SbomScopeSelection enforced) {
        return memberLock.packages().stream()
                .filter(lockPackage -> enforced.includes(SbomScopeGroup.of(lockPackage.scope())))
                .filter(lockPackage -> lockPackage.workspace().isEmpty())
                .map(lockPackage -> lockPackage.packageId() + ":" + lockPackage.version())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String coordinate(SbomComponent component) {
        return component.group() + ":" + component.name() + ":" + component.version();
    }
}
