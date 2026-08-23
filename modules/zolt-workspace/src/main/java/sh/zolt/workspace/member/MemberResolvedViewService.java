package sh.zolt.workspace.member;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockGraphRootSelector;
import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.publish.WorkspaceBomFamily;
import sh.zolt.workspace.publish.WorkspaceMemberPomLockProjection;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyLockProjection;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.service.WorkspaceMember;

/**
 * Projects one aggregate workspace lock into {@link MemberResolvedView}s — the single boundary every
 * member-facing command consumes.
 *
 * <p>The projections themselves were already written, once each, for the workspace-scoped commands
 * that needed them: the graph closure for {@code publish --workspace}'s SBOMs, the POM shape for its
 * POMs, the all-scope policy view and the packaging closure for {@code check --workspace}. What was
 * missing is that they were four unrelated entry points with four different call shapes, so a command
 * that ran in a member directory had no single thing to ask. This service is that thing: it owns the
 * per-member fan-out and hands back one value carrying the authoritative lock path and the member
 * identity beside each view.
 *
 * <p><strong>Not a fifth projection.</strong> Every field delegates to the existing projection that
 * already owns its rule, so the member view a command sees and the member view
 * {@code publish --workspace} / {@code check --workspace} act on are the same computation, not a
 * parallel one that can drift.
 *
 * <p><strong>The lock is an argument, never a derivation.</strong> This service never turns a
 * directory into a lock path; {@link MemberProjectionLoader} establishes ownership once, at the
 * command boundary, and passes both the aggregate and its path in.
 */
public final class MemberResolvedViewService {
    private final WorkspaceMemberPolicyResolver policyResolver;
    private final WorkspaceMemberPolicyLockProjection policyProjection;
    private final WorkspaceMemberSbomLockProjection graphProjection;
    private final WorkspaceMemberPomLockProjection publicationProjection;
    private final WorkspaceClasspathService classpathService;
    private final WorkspaceBomFamily bomFamily;

    public MemberResolvedViewService() {
        this(
                new WorkspaceMemberPolicyResolver(),
                new WorkspaceMemberPolicyLockProjection(),
                new WorkspaceMemberSbomLockProjection(),
                new WorkspaceMemberPomLockProjection(),
                new WorkspaceClasspathService(),
                new WorkspaceBomFamily());
    }

    public MemberResolvedViewService(
            WorkspaceMemberPolicyResolver policyResolver,
            WorkspaceMemberPolicyLockProjection policyProjection,
            WorkspaceMemberSbomLockProjection graphProjection,
            WorkspaceMemberPomLockProjection publicationProjection,
            WorkspaceClasspathService classpathService,
            WorkspaceBomFamily bomFamily) {
        this.policyResolver = policyResolver;
        this.policyProjection = policyProjection;
        this.graphProjection = graphProjection;
        this.publicationProjection = publicationProjection;
        this.classpathService = classpathService;
        this.bomFamily = bomFamily;
    }

    /** The effective config merge alone, for callers that already hold one and only need the views. */
    public WorkspaceMemberPolicyResolver policyResolver() {
        return policyResolver;
    }

    /**
     * One member's view of {@code aggregate}, taken at {@code authoritativeLockfile}.
     *
     * <p>{@code authoritativeLockfile} is passed rather than derived: this service must be usable from
     * a caller that read the lock from somewhere it already owns, and passing it keeps the "which file
     * governs this member" answer a single decision made at the boundary.
     */
    public MemberResolvedView view(
            Workspace workspace,
            ZoltLockfile aggregate,
            WorkspaceMember member,
            Path authoritativeLockfile) {
        ProjectConfig effectiveConfig = policyResolver.merge(workspace, member);
        return view(workspace, aggregate, member, effectiveConfig, authoritativeLockfile);
    }

    /** As {@link #view}, reusing an effective config the caller already merged. */
    public MemberResolvedView view(
            Workspace workspace,
            ZoltLockfile aggregate,
            WorkspaceMember member,
            ProjectConfig effectiveConfig,
            Path authoritativeLockfile) {
        String memberPath = member.path();
        boolean bom = effectiveConfig.packageSettings().mode() == PackageMode.BOM;
        return new MemberResolvedView(
                memberPath,
                member.directory(),
                authoritativeLockfile,
                effectiveConfig,
                bom,
                () -> graphProjection.project(memberPath, effectiveConfig, aggregate, workspace, policyResolver),
                () -> bom
                        ? bomFamily.familyLock(workspace, aggregate, member)
                        : classpathService
                                .packageLocksForMembers(workspace, aggregate, List.of(memberPath))
                                .get(memberPath),
                () -> policyProjection.project(memberPath, effectiveConfig, aggregate, workspace),
                () -> bom
                        ? bomFamily.familyLock(workspace, aggregate, member)
                        : publicationProjection.project(memberPath, effectiveConfig, aggregate),
                () -> graphRoots(
                        policyProjection.project(memberPath, effectiveConfig, aggregate, workspace),
                        aggregate));
    }

    /**
     * Views for a whole selection, in the given order. The per-member packaging closures are computed
     * in one pass so a selection pays the workspace classpath service's shared setup once.
     */
    public Map<String, MemberResolvedView> views(
            Workspace workspace,
            ZoltLockfile aggregate,
            Map<String, WorkspaceMember> members,
            List<String> memberPaths,
            Path authoritativeLockfile) {
        Map<String, MemberResolvedView> views = new LinkedHashMap<>();
        for (String memberPath : memberPaths) {
            WorkspaceMember member = members.get(memberPath);
            if (member == null) {
                throw new IllegalArgumentException(
                        "No workspace member is declared at `" + memberPath + "`.");
            }
            views.put(memberPath, view(workspace, aggregate, member, authoritativeLockfile));
        }
        return Map.copyOf(views);
    }

    /**
     * The authoritative lockfile for {@code workspace}. The ONE place the workspace member layer turns
     * a workspace root into a lock path — every {@link MemberResolvedView} carries the answer onward so
     * no consumer re-derives it.
     */
    public static Path authoritativeLockfile(Workspace workspace) {
        return ProjectLockfile.in(workspace.root());
    }

    /**
     * Authored roots come from the member-qualified v7 records; each otherwise-unrooted source graph
     * component contributes one deterministic resolver-injected root. Computed from the member's own
     * policy view so the roots and the policy facts can never describe different package sets.
     *
     * <p>Public because {@code WorkspaceMemberGraphRoots} — the workspace-shaped entry point the tree
     * and supply-chain reports already call — must select roots by this exact rule, not a copy of it.
     */
    public static List<String> graphRoots(ZoltLockfile memberLock, ZoltLockfile aggregate) {
        return LockGraphRootSelector.select(
                        memberLock.packages(),
                        memberLock.dependencyRoots(),
                        aggregate.packages(),
                        "zolt resolve --workspace")
                .stream()
                .map(LockDependencyEdge::of)
                .map(LockDependencyEdge::encode)
                .sorted()
                .toList();
    }
}
