package sh.zolt.quality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import sh.zolt.build.PackageException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.WorkspaceGraphLockCapability;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishException;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import sh.zolt.workspace.publish.WorkspaceBomFamily;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyLockProjection;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;

/**
 * Loads and projects the workspace lock once for every graph-dependent quality check.
 */
final class WorkspaceQualityProjectionService {
    private final ZoltLockfileReader lockfileReader;
    private final WorkspaceMemberPolicyResolver policyResolver;
    private final WorkspaceMemberPolicyLockProjection policyProjection;
    private final WorkspaceMemberSbomLockProjection sbomProjection;
    private final WorkspaceClasspathService classpathService;
    private final PackagePlanService packagePlanService;
    private final WorkspaceBomFamily bomFamily;

    WorkspaceQualityProjectionService(ZoltLockfileReader lockfileReader) {
        this(lockfileReader, new PackagePlanService());
    }

    WorkspaceQualityProjectionService(
            ZoltLockfileReader lockfileReader,
            PackagePlanService packagePlanService) {
        this(
                lockfileReader,
                new WorkspaceMemberPolicyResolver(),
                new WorkspaceMemberPolicyLockProjection(),
                new WorkspaceMemberSbomLockProjection(),
                new WorkspaceClasspathService(),
                packagePlanService,
                new WorkspaceBomFamily());
    }

    WorkspaceQualityProjectionService(
            ZoltLockfileReader lockfileReader,
            WorkspaceMemberPolicyResolver policyResolver,
            WorkspaceMemberPolicyLockProjection policyProjection,
            WorkspaceMemberSbomLockProjection sbomProjection,
            WorkspaceClasspathService classpathService,
            PackagePlanService packagePlanService,
            WorkspaceBomFamily bomFamily) {
        this.lockfileReader = lockfileReader;
        this.policyResolver = policyResolver;
        this.policyProjection = policyProjection;
        this.sbomProjection = sbomProjection;
        this.classpathService = classpathService;
        this.packagePlanService = packagePlanService;
        this.bomFamily = bomFamily;
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
        Path lockfilePath = workspace.root().resolve("zolt.lock");
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
            Map<String, ZoltLockfile> packageLocks = includePackagePlans
                    ? classpathService.packageLocksForMembers(
                            workspace,
                            aggregate,
                            selection.includedMembers())
                    : Map.of();
            for (String memberPath : selection.includedMembers()) {
                WorkspaceMember member = members.get(memberPath);
                ProjectConfig effectiveConfig = policyResolver.merge(workspace, member);
                ZoltLockfile policyLock =
                        policyProjection.project(memberPath, effectiveConfig, aggregate, workspace);
                ZoltLockfile sbomLock =
                        sbomProjection.project(memberPath, effectiveConfig, aggregate, workspace, policyResolver);
                Optional<PackagePlan> packagePlan = includePackagePlans
                        ? Optional.of(packagePlanService.plan(
                                member.directory(),
                                effectiveConfig,
                                effectiveConfig.packageSettings().mode()
                                                == sh.zolt.project.PackageMode.BOM
                                        ? bomFamily.familyLock(
                                                workspace,
                                                aggregate,
                                                member)
                                        : packageLocks.get(memberPath),
                                cacheRoot))
                        : Optional.empty();
                projected.put(
                        memberPath,
                        new WorkspaceMemberQualityView(
                                member,
                                effectiveConfig,
                                policyLock,
                                sbomLock,
                                packagePlan));
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
