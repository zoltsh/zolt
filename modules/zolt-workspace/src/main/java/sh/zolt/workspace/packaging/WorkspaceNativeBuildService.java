package sh.zolt.workspace.packaging;

import sh.zolt.build.nativeimage.NativeBuildResult;
import sh.zolt.build.nativeimage.NativeBuildService;
import sh.zolt.build.NativeImageException;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectVersionOverride;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WorkspaceNativeBuildService {
    private final WorkspacePackageService workspacePackageService;
    private final NativeBuildService nativeBuildService;

    public WorkspaceNativeBuildService() {
        this(new ResolveService(), FrameworkPackageAugmenter.none());
    }

    public WorkspaceNativeBuildService(ResolveService resolveService, FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(resolveService, frameworkPackageAugmenter, new PackagePlanService());
    }

    public WorkspaceNativeBuildService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService) {
        this(resolveService, frameworkPackageAugmenter, packagePlanService, BuildProvenanceSource.empty());
    }

    public WorkspaceNativeBuildService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService,
            BuildProvenanceSource provenanceSource) {
        this(
                new WorkspacePackageService(resolveService, frameworkPackageAugmenter, packagePlanService, provenanceSource),
                new NativeBuildService(provenanceSource));
    }

    WorkspaceNativeBuildService(
            WorkspacePackageService workspacePackageService,
            NativeBuildService nativeBuildService) {
        this.workspacePackageService = workspacePackageService;
        this.nativeBuildService = nativeBuildService;
    }

    public WorkspaceNativeBuildService withJdkCheckers(WorkspaceJdkCheckerResolver jdkCheckers) {
        return new WorkspaceNativeBuildService(
                workspacePackageService.withJdkCheckers(jdkCheckers),
                nativeBuildService);
    }

    public WorkspaceNativeBuildResult buildNative(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            Path nativeImageExecutable) {
        return buildNative(
                startDirectory,
                cacheRoot,
                selectionRequest,
                nativeImageExecutable,
                () -> {
                });
    }

    public WorkspaceNativeBuildResult buildNative(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            Path nativeImageExecutable,
            Runnable progress) {
        return buildNative(
                startDirectory,
                cacheRoot,
                selectionRequest,
                NativeImageExecutableResolver.fixed(nativeImageExecutable),
                progress);
    }

    public WorkspaceNativeBuildResult buildNative(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            NativeImageExecutableResolver nativeImageExecutableResolver,
            Runnable progress) {
        return WorkspaceMutationLock.withWorkspaceLock(startDirectory, () -> {
            WorkspaceBuildPlan plan = planNative(startDirectory, cacheRoot, selectionRequest);
            WorkspaceBuildResult buildResult = buildNativeInputs(plan, cacheRoot);
            WorkspacePackageResult packageResult = packageNativeInputs(plan, buildResult);
            return buildNativeImages(plan, packageResult, nativeImageExecutableResolver, progress);
        });
    }

    public WorkspaceBuildPlan planNative(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest) {
        return workspacePackageService.planPackages(
                WorkspacePlanTarget.at(startDirectory), cacheRoot, selectionRequest);
    }

    public WorkspaceBuildResult buildNativeInputs(WorkspaceBuildPlan plan, Path cacheRoot) {
        return workspacePackageService.buildPackageInputs(plan, cacheRoot);
    }

    public WorkspacePackageResult packageNativeInputs(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult) {
        return workspacePackageService.packageBuiltJars(
                plan,
                buildResult,
                Optional.of(PackageMode.THIN));
    }

    public WorkspaceNativeBuildResult buildNativeImages(
            WorkspaceBuildPlan plan,
            WorkspacePackageResult packageResult,
            Path nativeImageExecutable) {
        return buildNativeImages(plan, packageResult, nativeImageExecutable, () -> {
        });
    }

    public WorkspaceNativeBuildResult buildNativeImages(
            WorkspaceBuildPlan plan,
            WorkspacePackageResult packageResult,
            Path nativeImageExecutable,
            Runnable progress) {
        return buildNativeImages(
                plan,
                packageResult,
                NativeImageExecutableResolver.fixed(nativeImageExecutable),
                progress);
    }

    public WorkspaceNativeBuildResult buildNativeImages(
            WorkspaceBuildPlan plan,
            WorkspacePackageResult packageResult,
            NativeImageExecutableResolver nativeImageExecutableResolver,
            Runnable progress) {
        plan.requireInputsCurrent();
        Map<String, WorkspaceMember> membersByPath = membersByPath(plan.workspace());
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath = buildsByPath(packageResult);
        List<WorkspaceNativeBuildResult.MemberNativeBuildResult> results = new ArrayList<>();
        for (WorkspacePackageResult.MemberPackageResult memberPackage : packageResult.members()) {
            WorkspaceMember member = membersByPath.get(memberPackage.member());
            WorkspaceBuildResult.MemberBuildResult memberBuild = buildsByPath.get(memberPackage.member());
            requireMainClass(member);
            ProjectConfig config = ProjectVersionOverride.apply(member.config());
            Path nativeImageExecutable = nativeImageExecutableResolver.resolve(plan.workspace(), member, config);
            NativeBuildResult result = nativeBuildService.buildNativeImage(
                    member.directory(),
                    config,
                    memberPackage.result(),
                    memberBuild.classpaths().runtime().entries(),
                    nativeImageExecutable,
                    progress);
            results.add(new WorkspaceNativeBuildResult.MemberNativeBuildResult(member.path(), result));
        }
        return new WorkspaceNativeBuildResult(
                packageResult.resolveResult(),
                packageResult.builtMembers(),
                results);
    }

    @FunctionalInterface
    public interface NativeImageExecutableResolver {
        Path resolve(Workspace workspace, WorkspaceMember member, ProjectConfig config);

        static NativeImageExecutableResolver fixed(Path executable) {
            return (workspace, member, config) -> executable;
        }
    }

    private static void requireMainClass(WorkspaceMember member) {
        if (member.config().project().main().isEmpty()) {
            throw new NativeImageException(
                    "Workspace member `"
                            + member.path()
                            + "` has no main class configured. Add [project].main to its zolt.toml or choose an application member.");
        }
    }

    private static Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath(WorkspacePackageResult result) {
        Map<String, WorkspaceBuildResult.MemberBuildResult> builds = new LinkedHashMap<>();
        for (WorkspaceBuildResult.MemberBuildResult member : result.builtMembers()) {
            builds.put(member.member(), member);
        }
        return builds;
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }
}
