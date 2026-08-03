package sh.zolt.cli.command;

import sh.zolt.build.BuildService;
import sh.zolt.build.coverage.CoverageService;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.build.run.RunPackageService;
import sh.zolt.build.run.RunService;
import sh.zolt.build.nativeimage.NativeBuildService;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.cli.command.CommandServiceBundles.*;
import sh.zolt.cli.command.CommandServiceClusters.*;
import sh.zolt.cli.net.CommandNetwork;
import sh.zolt.framework.FrameworkBuildAugmenter;
import sh.zolt.framework.FrameworkPackageAugmenter;
import sh.zolt.framework.FrameworkRunAugmenter;
import sh.zolt.framework.FrameworkTestRunner;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.quarkus.QuarkusBuildAugmenter;
import sh.zolt.quarkus.QuarkusDependencyRequestPlanner;
import sh.zolt.quarkus.QuarkusPackageAugmenter;
import sh.zolt.quarkus.QuarkusPackagePlanRules;
import sh.zolt.quarkus.QuarkusRunAugmenter;
import sh.zolt.quarkus.testworker.QuarkusFrameworkTestRunner;
import sh.zolt.provenance.BuildProvenanceSource;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.coverage.WorkspaceCoverageService;
import sh.zolt.workspace.packaging.WorkspaceNativeBuildService;
import sh.zolt.workspace.packaging.WorkspacePackageService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.packaging.WorkspaceRunPackageService;
import sh.zolt.workspace.run.WorkspaceRunService;
import sh.zolt.workspace.service.WorkspaceTestService;
import java.util.List;

public final class CommandFrameworkServices {
    private CommandFrameworkServices() {}

    static ResolveService resolveService() {
        return new ResolveService(new QuarkusDependencyRequestPlanner(), CommandNetwork.repositoryClient());
    }

    static BuildProvenanceSource provenanceSource() {
        return CommandBuildProvenance.source();
    }

    static CommandConfigEditServices configEditServices() {
        return new CommandConfigEditServices(
                new ZoltTomlParser(),
                new ZoltTomlWriter(),
                resolveService());
    }

    public static CommandDependencyEditServices dependencyEditCommandServices() {
        return new CommandDependencyEditServices(
                new CoordinateParser(),
                configEditServices());
    }

    public static CommandVersionAliasServices versionAliasCommandServices() {
        return new CommandVersionAliasServices(configEditServices());
    }

    static WorkspaceResolveService workspaceResolveService() {
        return new WorkspaceResolveService(resolveService());
    }

    public static CommandResolveServices resolveCommandServices() {
        ResolveService resolveService = resolveService();
        return new CommandResolveServices(
                resolveService,
                new WorkspaceResolveService(resolveService));
    }

    static WorkspaceBuildService workspaceBuildService() {
        return workspaceBuildService(buildFrameworkServices());
    }

    static BuildService buildService() {
        return buildService(buildFrameworkServices());
    }

    static FrameworkBuildAugmenter buildAugmenter() {
        return buildFrameworkServices().frameworkBuildAugmenter();
    }

    static CommandBuildFrameworkServices buildFrameworkServices() {
        return new CommandBuildFrameworkServices(new QuarkusBuildAugmenter(), resolveService());
    }

    public static CommandBuildServices buildCommandServices() {
        CommandBuildFrameworkServices buildFrameworkServices = buildFrameworkServices();
        return new CommandBuildServices(
                buildService(buildFrameworkServices),
                workspaceBuildService(buildFrameworkServices),
                buildFrameworkServices.frameworkBuildAugmenter());
    }

    private static BuildService buildService(CommandBuildFrameworkServices buildFrameworkServices) {
        return new BuildService(buildFrameworkServices.resolveService(), provenanceSource());
    }

    private static WorkspaceBuildService workspaceBuildService(CommandBuildFrameworkServices buildFrameworkServices) {
        return new WorkspaceBuildService(buildFrameworkServices.resolveService(), provenanceSource());
    }

    public static PackagePlanService packagePlanService() {
        return new PackagePlanService(List.of(new QuarkusPackagePlanRules()));
    }

    static CommandPackageFrameworkServices packageFrameworkServices() {
        return new CommandPackageFrameworkServices(new QuarkusPackageAugmenter(), packagePlanService());
    }

    public static CommandPackageServices packageCommandServices() {
        ResolveService resolveService = resolveService();
        CommandPackageFrameworkServices packageFrameworkServices = packageFrameworkServices();
        PackagePlanService packagePlanService = packageFrameworkServices.packagePlanService();
        FrameworkPackageAugmenter packageAugmenter = packageFrameworkServices.packageAugmenter();
        BuildProvenanceSource provenanceSource = provenanceSource();
        return new CommandPackageServices(
                packagePlanService,
                new PackageService(resolveService, packageAugmenter, packagePlanService, provenanceSource),
                new BuildService(resolveService, provenanceSource),
                new WorkspacePackageService(resolveService, packageAugmenter, packagePlanService, provenanceSource));
    }

    public static CommandQualityServices qualityCommandServices() {
        return CommandQualityPublishServices.qualityCommandServices();
    }

    public static CommandPublishServices publishCommandServices() {
        return CommandQualityPublishServices.publishCommandServices();
    }

    static RunService runService() {
        return runService(runFrameworkServices());
    }

    static RunService runService(FrameworkRunAugmenter frameworkRunAugmenter) {
        return runService(new CommandRunFrameworkServices(frameworkRunAugmenter, resolveService()));
    }

    static CommandRunFrameworkServices runFrameworkServices() {
        return new CommandRunFrameworkServices(new QuarkusRunAugmenter(), resolveService());
    }

    private static RunService runService(CommandRunFrameworkServices runFrameworkServices) {
        return new RunService(runFrameworkServices.frameworkRunAugmenter());
    }

    public static CommandRunServices runCommandServices() {
        CommandRunFrameworkServices runFrameworkServices = runFrameworkServices();
        return new CommandRunServices(
                runService(runFrameworkServices),
                workspaceRunService(runFrameworkServices));
    }

    static PackageService packageService() {
        return packageService(packageFrameworkServices());
    }

    static PackageService packageService(FrameworkPackageAugmenter frameworkPackageAugmenter) {
        return packageService(new CommandPackageFrameworkServices(frameworkPackageAugmenter, packagePlanService()));
    }

    private static PackageService packageService(CommandPackageFrameworkServices packageFrameworkServices) {
        return new PackageService(
                resolveService(),
                packageFrameworkServices.packageAugmenter(),
                packageFrameworkServices.packagePlanService(),
                provenanceSource());
    }

    static RunPackageService runPackageService() {
        return runPackageService(packageFrameworkServices());
    }

    public static CommandRunPackageServices runPackageCommandServices() {
        CommandPackageFrameworkServices packageFrameworkServices = packageFrameworkServices();
        return new CommandRunPackageServices(
                runPackageService(packageFrameworkServices),
                workspaceRunPackageService(packageFrameworkServices));
    }

    static RunPackageService runPackageService(FrameworkPackageAugmenter frameworkPackageAugmenter) {
        return runPackageService(new CommandPackageFrameworkServices(frameworkPackageAugmenter, packagePlanService()));
    }

    private static RunPackageService runPackageService(CommandPackageFrameworkServices packageFrameworkServices) {
        return new RunPackageService(
                resolveService(),
                packageFrameworkServices.packageAugmenter(),
                packageFrameworkServices.packagePlanService(),
                provenanceSource());
    }

    static WorkspacePackageService workspacePackageService() {
        return workspacePackageService(packageFrameworkServices());
    }

    static WorkspacePackageService workspacePackageService(FrameworkPackageAugmenter frameworkPackageAugmenter) {
        return workspacePackageService(
                new CommandPackageFrameworkServices(frameworkPackageAugmenter, packagePlanService()));
    }

    private static WorkspacePackageService workspacePackageService(
            CommandPackageFrameworkServices packageFrameworkServices) {
        return new WorkspacePackageService(
                resolveService(),
                packageFrameworkServices.packageAugmenter(),
                packageFrameworkServices.packagePlanService(),
                provenanceSource());
    }

    static WorkspaceNativeBuildService workspaceNativeBuildService() {
        CommandPackageFrameworkServices packageFrameworkServices = packageFrameworkServices();
        return new WorkspaceNativeBuildService(
                resolveService(),
                packageFrameworkServices.packageAugmenter(),
                packageFrameworkServices.packagePlanService(),
                provenanceSource());
    }

    public static CommandNativeServices nativeCommandServices() {
        return new CommandNativeServices(
                new ZoltTomlParser(),
                new NativeBuildService(provenanceSource()),
                workspaceNativeBuildService());
    }

    public static CommandCoverageServices coverageCommandServices() {
        CommandTestFrameworkServices testFrameworkServices = testFrameworkServices();
        CommandServiceBundles.TestRunServiceFactory testRunServiceFactory =
                CommandWorkspaceTestRunServices.persistentFactory(
                        testFrameworkServices);
        return new CommandCoverageServices(
                new ZoltTomlParser(),
                new CoverageService(),
                new WorkspaceCoverageService(
                        workspaceTestService(testFrameworkServices)),
                (compileChecker, runChecker) -> new CoverageService(
                        testRunService(testFrameworkServices, compileChecker, runChecker),
                        compileChecker),
                testRunServiceFactory);
    }

    static WorkspaceRunPackageService workspaceRunPackageService() {
        return workspaceRunPackageService(packageFrameworkServices());
    }

    static WorkspaceRunPackageService workspaceRunPackageService(FrameworkPackageAugmenter frameworkPackageAugmenter) {
        return workspaceRunPackageService(
                new CommandPackageFrameworkServices(frameworkPackageAugmenter, packagePlanService()));
    }

    private static WorkspaceRunPackageService workspaceRunPackageService(
            CommandPackageFrameworkServices packageFrameworkServices) {
        return new WorkspaceRunPackageService(
                resolveService(),
                packageFrameworkServices.packageAugmenter(),
                packageFrameworkServices.packagePlanService(),
                provenanceSource());
    }

    static WorkspaceRunService workspaceRunService() {
        return workspaceRunService(runFrameworkServices());
    }

    private static WorkspaceRunService workspaceRunService(CommandRunFrameworkServices runFrameworkServices) {
        return new WorkspaceRunService(runFrameworkServices.resolveService());
    }

    static CommandTestFrameworkServices testFrameworkServices() {
        return new CommandTestFrameworkServices(new QuarkusFrameworkTestRunner(), resolveService());
    }

    public static CommandTestServices testCommandServices() {
        CommandTestFrameworkServices testFrameworkServices = testFrameworkServices();
        return new CommandTestServices(
                testRunService(testFrameworkServices),
                workspaceTestService(testFrameworkServices),
                CommandWorkspaceTestRunServices.persistentFactory(
                        testFrameworkServices));
    }

    static TestRunService testRunService(FrameworkTestRunner frameworkTestRunner) {
        return testRunService(new CommandTestFrameworkServices(frameworkTestRunner, resolveService()));
    }

    private static TestRunService testRunService(CommandTestFrameworkServices testFrameworkServices) {
        return testRunService(testFrameworkServices, new sh.zolt.doctor.JdkDetector());
    }

    private static TestRunService testRunService(
            CommandTestFrameworkServices testFrameworkServices,
            JdkChecker jdkChecker) {
        return testRunService(testFrameworkServices, jdkChecker, jdkChecker);
    }

    private static TestRunService testRunService(
            CommandTestFrameworkServices testFrameworkServices,
            JdkChecker compileChecker,
            JdkChecker runChecker) {
        return CommandWorkspaceTestRunServices.once(
                testFrameworkServices,
                compileChecker,
                runChecker);
    }

    static TestRunService testRunService() {
        return testRunService(testFrameworkServices());
    }

    static WorkspaceTestService workspaceTestService(FrameworkTestRunner frameworkTestRunner) {
        return workspaceTestService(new CommandTestFrameworkServices(frameworkTestRunner, resolveService()));
    }

    private static WorkspaceTestService workspaceTestService(CommandTestFrameworkServices testFrameworkServices) {
        return new WorkspaceTestService(
                testFrameworkServices.resolveService(),
                testFrameworkServices.frameworkTestRunner());
    }

    static WorkspaceTestService workspaceTestService() {
        return workspaceTestService(testFrameworkServices());
    }
}
