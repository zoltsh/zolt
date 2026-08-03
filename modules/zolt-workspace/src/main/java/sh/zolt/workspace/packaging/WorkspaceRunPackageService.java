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
import sh.zolt.workspace.service.WorkspaceRunFiles;
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
        WorkspaceRunPackageSnapshot snapshot =
                WorkspaceMutationLock.withWorkspaceLock(startDirectory, () -> {
                    WorkspaceBuildPlan plan =
                            planRunPackages(
                                    startDirectory,
                                    cacheRoot,
                                    selectionRequest);
                    WorkspaceBuildResult buildResult =
                            buildRunPackageInputs(plan, cacheRoot);
                    WorkspacePackageResult packageResult =
                            packageRunPackageInputs(
                                    plan,
                                    buildResult,
                                    cacheRoot,
                                    packageModeOverride);
                    return snapshotRunPackages(plan, packageResult);
                });
        try (snapshot) {
            return runSnapshot(snapshot, arguments);
        }
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
        plan.requireInputsCurrent();
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
        try (WorkspaceRunPackageSnapshot snapshot =
                snapshotRunPackages(plan, packageResult)) {
            return runSnapshot(snapshot, arguments);
        }
    }

    public WorkspaceRunPackageSnapshot snapshotRunPackages(
            WorkspaceBuildPlan plan,
            WorkspacePackageResult packageResult) {
        return WorkspaceMutationLock.withLock(
                plan.workspace().root(),
                () -> snapshotRunPackagesLocked(plan, packageResult));
    }

    private WorkspaceRunPackageSnapshot snapshotRunPackagesLocked(
            WorkspaceBuildPlan plan,
            WorkspacePackageResult packageResult) {
        plan.requireInputsCurrent();
        Workspace workspace = plan.workspace();
        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        Map<String, WorkspaceBuildResult.MemberBuildResult> buildsByPath = buildsByPath(packageResult);

        WorkspaceRunFiles files = WorkspaceRunFiles.create(workspace.root());
        try {
            List<WorkspaceRunPackageSnapshot.MemberLaunch> launches =
                    new ArrayList<>();
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
                sh.zolt.build.packaging.PackageResult original =
                        memberPackage.result();
                sh.zolt.build.packaging.PackageResult captured =
                        withJarPath(
                                original,
                                files.capture(original.jarPath()));
                List<Path> runtimeEntries;
                if (launchPolicy.strategy()
                        == PackageLaunchPolicy.Strategy.CLASSPATH) {
                    for (WorkspaceBuildResult.MemberBuildResult built : packageResult.builtMembers()) {
                        files.capture(built.result().outputDirectory());
                    }
                    runtimeEntries = files.remap(
                            memberBuild.classpaths().runtime().entries());
                } else {
                    runtimeEntries = List.of();
                }
                launches.add(new WorkspaceRunPackageSnapshot.MemberLaunch(
                        memberPackage.member(),
                        memberBuild,
                        original,
                        captured,
                        runtimeEntries,
                        jdkStatus.java().orElseThrow(),
                        mainClass));
            }
            return new WorkspaceRunPackageSnapshot(
                    packageResult,
                    files,
                    launches);
        } catch (RuntimeException exception) {
            files.close();
            throw exception;
        }
    }

    public WorkspaceRunPackageResult runSnapshot(
            WorkspaceRunPackageSnapshot snapshot,
            List<String> arguments) {
        List<WorkspaceRunPackageResult.MemberRunPackageResult> results = new ArrayList<>();
        for (WorkspaceRunPackageSnapshot.MemberLaunch launch : snapshot.members()) {
            JavaRunResult javaRunResult = applicationLauncher.launch(
                    launch.java(),
                    launch.snapshotPackage(),
                    launch.runtimeEntries(),
                    launch.mainClass(),
                    arguments);
            results.add(new WorkspaceRunPackageResult.MemberRunPackageResult(
                    launch.member(),
                    new RunPackageResult(launch.originalPackage(), javaRunResult)));
        }
        return new WorkspaceRunPackageResult(
                snapshot.packageResult().resolveResult(),
                snapshot.packageResult().builtMembers(),
                results);
    }

    private static sh.zolt.build.packaging.PackageResult withJarPath(
            sh.zolt.build.packaging.PackageResult source,
            Path jarPath) {
        return new sh.zolt.build.packaging.PackageResult(
                source.buildResult(),
                source.mode(),
                jarPath,
                source.runtimeClasspathPath(),
                source.evidenceManifestPath(),
                source.entryCount(),
                source.hasMainClass(),
                source.applicationLayout(),
                source.artifacts(),
                source.mergeDecisions(),
                source.materializedInputs(),
                source.packagingReused());
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
