package sh.zolt.workspace.packaging;

import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackageOutputFingerprintIndex;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildRequirements;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.service.WorkspaceJdkCheckerResolver;
import sh.zolt.workspace.publish.WorkspaceBomPackager;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspaceSelection;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

public final class WorkspacePackageService {
    private final WorkspaceBuildService workspaceBuildService;
    private final PackageService packageService;
    private final PackagePlanService packagePlanService;
    private final WorkspaceClasspathService workspaceClasspathService;
    private final WorkspaceBomPackager bomPackager;
    private final WorkspacePackageExecutor packageExecutor;

    public WorkspacePackageService() {
        this(new JdkDetector());
    }

    public WorkspacePackageService(ResolveService resolveService, FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(resolveService, frameworkPackageAugmenter, new PackagePlanService());
    }

    public WorkspacePackageService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService) {
        this(new JdkDetector(), resolveService, frameworkPackageAugmenter, packagePlanService);
    }

    public WorkspacePackageService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService,
            BuildProvenanceSource provenanceSource) {
        this(new JdkDetector(), resolveService, frameworkPackageAugmenter, packagePlanService, provenanceSource);
    }

    WorkspacePackageService(JdkChecker jdkDetector) {
        this(jdkDetector, new ResolveService(), FrameworkPackageAugmenter.none());
    }

    WorkspacePackageService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(jdkDetector, resolveService, frameworkPackageAugmenter, new PackagePlanService());
    }

    WorkspacePackageService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService) {
        this(jdkDetector, resolveService, frameworkPackageAugmenter, packagePlanService, BuildProvenanceSource.empty());
    }

    WorkspacePackageService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService,
            BuildProvenanceSource provenanceSource) {
        this(
                new WorkspaceBuildService(jdkDetector, resolveService, provenanceSource),
                new PackageService(resolveService, frameworkPackageAugmenter, packagePlanService, provenanceSource),
                packagePlanService,
                new WorkspaceClasspathService(),
                new WorkspaceBomPackager(packagePlanService));
    }

    WorkspacePackageService(
            WorkspaceBuildService workspaceBuildService,
            PackageService packageService) {
        this(
                workspaceBuildService,
                packageService,
                new PackagePlanService(),
                new WorkspaceClasspathService());
    }

    WorkspacePackageService(
            WorkspaceBuildService workspaceBuildService,
            PackageService packageService,
            PackagePlanService packagePlanService,
            WorkspaceClasspathService workspaceClasspathService) {
        this(
                workspaceBuildService,
                packageService,
                packagePlanService,
                workspaceClasspathService,
                new WorkspaceBomPackager(packagePlanService));
    }

    WorkspacePackageService(
            WorkspaceBuildService workspaceBuildService,
            PackageService packageService,
            PackagePlanService packagePlanService,
            WorkspaceClasspathService workspaceClasspathService,
            WorkspaceBomPackager bomPackager) {
        this.workspaceBuildService = workspaceBuildService;
        this.packageService = packageService;
        this.packagePlanService = packagePlanService;
        this.workspaceClasspathService = workspaceClasspathService;
        this.bomPackager = bomPackager;
        this.packageExecutor = new WorkspacePackageExecutor();
    }

    public WorkspacePackageService withJdkCheckers(WorkspaceJdkCheckerResolver jdkCheckers) {
        return new WorkspacePackageService(
                workspaceBuildService.withJdkCheckers(jdkCheckers),
                packageService,
                packagePlanService,
                workspaceClasspathService,
                bomPackager);
    }

    public WorkspacePackageResult packageJars(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest) {
        return packageJars(startDirectory, cacheRoot, selectionRequest, Optional.empty());
    }

    public WorkspacePackageResult packageJars(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            Optional<PackageMode> packageModeOverride) {
        return WorkspaceMutationLock.withWorkspaceLock(startDirectory, () -> {
            WorkspaceBuildPlan plan = planPackages(
                    WorkspacePlanTarget.at(startDirectory), cacheRoot, selectionRequest);
            return packageBuiltJars(plan, buildPackageInputs(plan, cacheRoot), cacheRoot, packageModeOverride);
        });
    }

    public WorkspaceBuildPlan planPackages(
            WorkspacePlanTarget target,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest) {
        return workspaceBuildService.planBuild(target, cacheRoot, false, selectionRequest);
    }

    public WorkspaceBuildResult buildPackageInputs(WorkspaceBuildPlan plan, Path cacheRoot) {
        return workspaceBuildService.build(plan, cacheRoot, WorkspaceBuildRequirements.mainBuild());
    }

    public WorkspacePackageResult packageBuiltJars(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Optional<PackageMode> packageModeOverride) {
        return packageBuiltJars(plan, buildResult, Optional.empty(), packageModeOverride);
    }

    public WorkspacePackageResult packageBuiltJars(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            Optional<PackageMode> packageModeOverride) {
        return packageBuiltJars(plan, buildResult, Optional.of(cacheRoot), packageModeOverride);
    }

    private WorkspacePackageResult packageBuiltJars(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Optional<Path> cacheRoot,
            Optional<PackageMode> packageModeOverride) {
        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(plan.workspace().root())) {
            return packageBuiltJarsLocked(
                    plan,
                    buildResult,
                    cacheRoot,
                    packageModeOverride);
        }
    }

    private WorkspacePackageResult packageBuiltJarsLocked(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Optional<Path> cacheRoot,
            Optional<PackageMode> packageModeOverride) {
        Workspace workspace = plan.requireInputsCurrent().workspace();
        WorkspaceSelection selection = plan.selection();
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath = buildsByPath(buildResult);
        PackageOutputFingerprintIndex outputFingerprints =
                new PackageOutputFingerprintIndex();
        List<Callable<PackagedMember>> tasks = new ArrayList<>();
        for (String memberPath : selection.selectedMembers()) {
            WorkspaceMember member = membersByPath.get(memberPath);
            WorkspaceBuildResult.MemberBuildResult memberBuild = buildsByPath.get(memberPath);
            ProjectConfig memberConfig = packageModeOverride
                    .map(mode -> member.config().withPackageSettings(
                            member.config().packageSettings().withMode(mode)))
                    .orElse(member.config());
            tasks.add(() -> packageMember(
                    workspace,
                    plan,
                    member,
                    memberBuild,
                    memberConfig,
                    cacheRoot,
                    outputFingerprints));
        }
        WorkspacePackageExecutor.Result<PackagedMember> packaged =
                packageExecutor.execute(tasks);
        return new WorkspacePackageResult(
                buildResult.resolveResult(),
                packageBuildResults(buildResult.members(), packaged.values()),
                packaged.values().stream()
                        .map(PackagedMember::result)
                        .toList(),
                packaged.maxWorkers(),
                outputFingerprints.metrics());
    }

    private PackagedMember packageMember(
            Workspace workspace,
            WorkspaceBuildPlan plan,
            WorkspaceMember member,
            WorkspaceBuildResult.MemberBuildResult memberBuild,
            ProjectConfig memberConfig,
            Optional<Path> cacheRoot,
            PackageOutputFingerprintIndex outputFingerprints) {
        if (memberConfig.packageSettings().mode() == PackageMode.BOM) {
            return new PackagedMember(
                    new WorkspacePackageResult.MemberPackageResult(
                            member.path(),
                            bomPackager.packageBom(
                                    member,
                                    workspace,
                                    plan.lockfile(),
                                    memberBuild.result())),
                    Optional.empty());
        }
        WorkspaceClasspathService.PackageInputs packageInputs =
                workspaceClasspathService.packageInputsFor(
                        plan.executionContext(),
                        member.path(),
                        memberConfig.packageSettings().tests());
        PackagePlan packagePlan = cacheRoot
                .map(root -> packagePlanService.plan(
                        member.directory(),
                        memberConfig,
                        packageInputs.lockfile(),
                        root,
                        outputFingerprints))
                .orElseGet(() -> packagePlanService.plan(
                        member.directory(),
                        memberConfig,
                        packageInputs.lockfile(),
                        sh.zolt.cache.LocalArtifactCache.defaultRoot(),
                        outputFingerprints));
        return new PackagedMember(
                new WorkspacePackageResult.MemberPackageResult(
                        member.path(),
                        packageService.packageJar(
                                member.directory(),
                                memberConfig,
                                memberBuild.result(),
                                cacheRoot,
                                packageInputs.classpaths(),
                                packageInputs.packages(),
                                packagePlan,
                                outputFingerprints)),
                Optional.of(packageInputs));
    }

    private static List<WorkspaceBuildResult.MemberBuildResult> packageBuildResults(
            List<WorkspaceBuildResult.MemberBuildResult> buildResults,
            List<PackagedMember> packagedMembers) {
        Map<String, WorkspaceClasspathService.PackageInputs> packageInputsByMember =
                new LinkedHashMap<>();
        for (PackagedMember packagedMember : packagedMembers) {
            packagedMember.packageInputs().ifPresent(inputs ->
                    packageInputsByMember.put(
                            packagedMember.result().member(),
                            inputs));
        }
        return buildResults.stream()
                .map(build -> {
                    WorkspaceClasspathService.PackageInputs packageInputs =
                            packageInputsByMember.get(build.member());
                    if (packageInputs == null) {
                        return build;
                    }
                    return new WorkspaceBuildResult.MemberBuildResult(
                            build.member(),
                            build.result(),
                            packageInputs.classpaths(),
                            packageInputs.packages());
                })
                .toList();
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }

    private static Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath(WorkspaceBuildResult result) {
        Map<String, WorkspaceBuildResult.MemberBuildResult> builds = new LinkedHashMap<>();
        for (WorkspaceBuildResult.MemberBuildResult member : result.members()) {
            builds.put(member.member(), member);
        }
        return builds;
    }

    private record PackagedMember(
            WorkspacePackageResult.MemberPackageResult result,
            Optional<WorkspaceClasspathService.PackageInputs> packageInputs) {
    }
}
