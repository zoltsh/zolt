package sh.zolt.quality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.WorkspaceGraphLockCapability;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.publish.PublishException;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.member.MemberResolvedView;
import sh.zolt.workspace.member.MemberResolvedViewService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;

/**
 * Loads the workspace lock once and projects it into the {@link MemberResolvedView} every
 * graph-dependent quality check reads.
 *
 * <p>The projections themselves are not this class's: {@link MemberResolvedViewService} owns them, and
 * owns them for the whole member-facing command matrix, so a member's dependency-policy facts here and
 * that member's SBOM under {@code zolt sbom} describe the same package set by construction. What stays
 * here is the quality layer's own concern — the package plan {@code package-contents} needs, which
 * takes a cache root and a packager and is not part of a member's view of the lock.
 */
final class WorkspaceQualityProjectionService {
    private final ZoltLockfileReader lockfileReader;
    private final MemberResolvedViewService viewService;
    private final PackagePlanService packagePlanService;

    WorkspaceQualityProjectionService(ZoltLockfileReader lockfileReader) {
        this(lockfileReader, new PackagePlanService());
    }

    WorkspaceQualityProjectionService(
            ZoltLockfileReader lockfileReader,
            PackagePlanService packagePlanService) {
        this(lockfileReader, new MemberResolvedViewService(), packagePlanService);
    }

    WorkspaceQualityProjectionService(
            ZoltLockfileReader lockfileReader,
            MemberResolvedViewService viewService,
            PackagePlanService packagePlanService) {
        this.lockfileReader = lockfileReader;
        this.viewService = viewService;
        this.packagePlanService = packagePlanService;
    }

    WorkspaceQualityProjection project(
            Workspace workspace,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> members) {
        return project(workspace, selection, members, false);
    }

    WorkspaceQualityProjection project(
            Workspace workspace,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> members,
            boolean includePackagePlans) {
        return project(
                workspace,
                selection,
                members,
                includePackagePlans,
                LocalArtifactCache.defaultRoot());
    }

    WorkspaceQualityProjection project(
            Workspace workspace,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> members,
            boolean includePackagePlans,
            Path cacheRoot) {
        Path lockfilePath = MemberResolvedViewService.authoritativeLockfile(workspace);
        if (!Files.isRegularFile(lockfilePath)) {
            throw new WorkspaceQualityProjectionException(
                    "Workspace zolt.lock is missing.",
                    "Run `zolt resolve --workspace`.");
        }

        ZoltLockfile aggregate;
        try {
            aggregate = lockfileReader.read(lockfilePath);
        } catch (LockfileReadException exception) {
            throw new WorkspaceQualityProjectionException(
                    exception.getMessage(),
                    "Run `zolt resolve --workspace`.");
        }
        try {
            WorkspaceGraphLockCapability.requireMemberGraphEvidence(aggregate);
        } catch (ActionableException exception) {
            throw new WorkspaceQualityProjectionException(
                    exception.error().summary(),
                    exception.error().remediation());
        }

        Map<String, WorkspaceMemberQualityView> projected = new LinkedHashMap<>();
        try {
            Map<String, MemberResolvedView> views = viewService.views(
                    workspace,
                    aggregate,
                    members,
                    List.copyOf(selection.includedMembers()),
                    lockfilePath);
            for (String memberPath : selection.includedMembers()) {
                MemberResolvedView view = views.get(memberPath);
                Optional<PackagePlan> packagePlan = includePackagePlans
                        ? Optional.of(packagePlanService.plan(
                                view.memberDirectory(),
                                view.effectiveConfig(),
                                view.packageLock(),
                                cacheRoot))
                        : Optional.empty();
                projected.put(
                        memberPath,
                        new WorkspaceMemberQualityView(members.get(memberPath), view, packagePlan));
            }
        } catch (LockDependencyGraphException
                | PackageException
                | PublishException
                | ResolveException exception) {
            throw new WorkspaceQualityProjectionException(
                    exception.getMessage(),
                    "Run `zolt resolve --workspace` to regenerate member-qualified graph evidence.");
        }
        return new WorkspaceQualityProjection(projected);
    }
}
