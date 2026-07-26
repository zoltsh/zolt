package sh.zolt.quality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import sh.zolt.build.PackageException;
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

    WorkspaceQualityProjectionService(ZoltLockfileReader lockfileReader) {
        this(
                lockfileReader,
                new WorkspaceMemberPolicyResolver(),
                new WorkspaceMemberPolicyLockProjection(),
                new WorkspaceMemberSbomLockProjection(),
                new WorkspaceClasspathService(),
                new PackagePlanService());
    }

    WorkspaceQualityProjectionService(
            ZoltLockfileReader lockfileReader,
            WorkspaceMemberPolicyResolver policyResolver,
            WorkspaceMemberPolicyLockProjection policyProjection,
            WorkspaceMemberSbomLockProjection sbomProjection,
            WorkspaceClasspathService classpathService,
            PackagePlanService packagePlanService) {
        this.lockfileReader = lockfileReader;
        this.policyResolver = policyResolver;
        this.policyProjection = policyProjection;
        this.sbomProjection = sbomProjection;
        this.classpathService = classpathService;
        this.packagePlanService = packagePlanService;
    }

    WorkspaceQualityProjection project(
            Workspace workspace,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> members) {
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
            Map<String, ZoltLockfile> packageLocks =
                    classpathService.packageLocksForMembers(
                            workspace,
                            aggregate,
                            selection.includedMembers());
            for (String memberPath : selection.includedMembers()) {
                WorkspaceMember member = members.get(memberPath);
                ProjectConfig effectiveConfig = policyResolver.merge(workspace, member);
                ZoltLockfile policyLock =
                        policyProjection.project(memberPath, effectiveConfig, aggregate, workspace);
                ZoltLockfile sbomLock =
                        sbomProjection.project(memberPath, effectiveConfig, aggregate, workspace, policyResolver);
                PackagePlan packagePlan = packagePlanService.plan(
                        member.directory(),
                        effectiveConfig,
                        packageLocks.get(memberPath));
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
