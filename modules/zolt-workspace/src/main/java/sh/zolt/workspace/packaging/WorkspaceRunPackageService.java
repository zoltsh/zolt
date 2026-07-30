package sh.zolt.workspace.packaging;

import sh.zolt.build.RunPackageException;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.run.PackageApplicationLauncher;
import sh.zolt.build.run.PackageLaunchPolicy;
import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.build.run.RunPackageResult;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkDetector;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.project.PackageMode;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WorkspaceRunPackageService {
    private final WorkspacePackageService workspacePackageService;
    private final JdkChecker jdkDetector;
    private final PackageApplicationLauncher applicationLauncher;

    public WorkspaceRunPackageService() {
        this(new JdkDetector());
    }

    public WorkspaceRunPackageService(ResolveService resolveService, FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(resolveService, frameworkPackageAugmenter, new PackagePlanService());
    }

    public WorkspaceRunPackageService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService) {
        this(new JdkDetector(), resolveService, frameworkPackageAugmenter, packagePlanService);
    }

    public WorkspaceRunPackageService(
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService,
            BuildProvenanceSource provenanceSource) {
        this(new JdkDetector(), resolveService, frameworkPackageAugmenter, packagePlanService, provenanceSource);
    }

    WorkspaceRunPackageService(JdkChecker jdkDetector) {
        this(jdkDetector, new ResolveService(), FrameworkPackageAugmenter.none());
    }

    WorkspaceRunPackageService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter) {
        this(jdkDetector, resolveService, frameworkPackageAugmenter, new PackagePlanService());
    }

    WorkspaceRunPackageService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService) {
        this(jdkDetector, resolveService, frameworkPackageAugmenter, packagePlanService, BuildProvenanceSource.empty());
    }

    WorkspaceRunPackageService(
            JdkChecker jdkDetector,
            ResolveService resolveService,
            FrameworkPackageAugmenter frameworkPackageAugmenter,
            PackagePlanService packagePlanService,
            BuildProvenanceSource provenanceSource) {
        this(
                new WorkspacePackageService(
                        jdkDetector,
                        resolveService,
                        frameworkPackageAugmenter,
                        packagePlanService,
                        provenanceSource),
                jdkDetector,
                new JavaRunner());
    }

    WorkspaceRunPackageService(
            WorkspacePackageService workspacePackageService,
            JdkChecker jdkDetector,
            JavaRunner javaRunner) {
        this.workspacePackageService = workspacePackageService;
        this.jdkDetector = jdkDetector;
        this.applicationLauncher = new PackageApplicationLauncher(javaRunner);
    }

    public WorkspaceRunPackageResult runPackages(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            List<String> arguments) {
        return runPackages(startDirectory, cacheRoot, selectionRequest, arguments, Optional.empty());
    }

    public WorkspaceRunPackageResult runPackages(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest,
            List<String> arguments,
            Optional<PackageMode> packageModeOverride) {
        WorkspaceBuildPlan plan = planRunPackages(startDirectory, cacheRoot, selectionRequest);
        return WorkspaceMutationLock.withLock(plan.workspace().root(), () -> {
            WorkspaceBuildResult buildResult = buildRunPackageInputs(plan, cacheRoot);
            WorkspacePackageResult packageResult =
                    packageRunPackageInputs(
                            plan,
                            buildResult,
                            cacheRoot,
                            packageModeOverride);
            return runPackagedMembers(plan, packageResult, arguments);
        });
    }

    public WorkspaceBuildPlan planRunPackages(
            Path startDirectory,
            Path cacheRoot,
            WorkspaceSelectionRequest selectionRequest) {
        return workspacePackageService.planPackages(startDirectory, cacheRoot, selectionRequest);
    }

    public WorkspaceBuildResult buildRunPackageInputs(WorkspaceBuildPlan plan, Path cacheRoot) {
        return workspacePackageService.buildPackageInputs(plan, cacheRoot);
    }

    public WorkspacePackageResult packageRunPackageInputs(
            WorkspaceBuildPlan plan,
            WorkspaceBuildResult buildResult,
            Path cacheRoot,
            Optional<PackageMode> packageModeOverride) {
        requireRunnableModes(plan, packageModeOverride);
        return workspacePackageService.packageBuiltJars(
                plan,
                buildResult,
                cacheRoot,
                packageModeOverride);
    }

    public WorkspaceRunPackageResult runPackagedMembers(
            WorkspaceBuildPlan plan,
            WorkspacePackageResult packageResult,
            List<String> arguments) {
        Workspace workspace = plan.workspace();
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath = buildsByPath(packageResult);

        List<WorkspaceRunPackageResult.MemberRunPackageResult> results = new ArrayList<>();
        for (WorkspacePackageResult.MemberPackageResult memberPackage : packageResult.members()) {
            WorkspaceMember member = membersByPath.get(memberPackage.member());
            WorkspaceBuildResult.MemberBuildResult memberBuild = buildsByPath.get(memberPackage.member());
            PackageLaunchPolicy.Decision launchPolicy =
                    PackageLaunchPolicy.forMode(
                            memberPackage.result().mode());
            if (launchPolicy.strategy()
                    == PackageLaunchPolicy.Strategy.REJECT) {
                throw new RunPackageException(
                        "Workspace member `"
                                + member.path()
                                + "` cannot be run: "
                                + launchPolicy.rejection());
            }
            String mainClass = member.config().project().main().orElseThrow(() -> new RunPackageException(
                    "Workspace member `"
                            + member.path()
                            + "` has no main class configured. Add [project].main to its zolt.toml or choose an application member."));
            JdkStatus jdkStatus = jdkDetector.detect(member.config().project().java());
            if (!jdkStatus.ok()) {
                throw new RunPackageException("JDK check failed. " + String.join(" ", jdkStatus.problems()));
            }
            JavaRunResult javaRunResult = applicationLauncher.launch(
                    jdkStatus.java().orElseThrow(),
                    memberPackage.result(),
                    memberBuild.classpaths().runtime().entries(),
                    mainClass,
                    arguments);
            results.add(new WorkspaceRunPackageResult.MemberRunPackageResult(
                    memberPackage.member(),
                    new RunPackageResult(memberPackage.result(), javaRunResult)));
        }
        return new WorkspaceRunPackageResult(packageResult.resolveResult(), packageResult.builtMembers(), results);
    }

    private static void requireRunnableModes(
            WorkspaceBuildPlan plan,
            Optional<PackageMode> packageModeOverride) {
        Map<String, WorkspaceMember> members =
                membersByPath(plan.workspace());
        for (String memberPath : plan.selection().selectedMembers()) {
            WorkspaceMember member = members.get(memberPath);
            PackageMode mode = packageModeOverride.orElse(
                    member.config().packageSettings().mode());
            PackageLaunchPolicy.Decision decision =
                    PackageLaunchPolicy.forMode(mode);
            if (decision.strategy()
                    == PackageLaunchPolicy.Strategy.REJECT) {
                throw new RunPackageException(
                        "Workspace member `"
                                + member.path()
                                + "` cannot be run: "
                                + decision.rejection());
            }
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
