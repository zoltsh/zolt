package sh.zolt.cli.command.testcmd;

import sh.zolt.build.BuildException;
import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.JavacException;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.build.testruntime.compile.TestCompileResultWithClasspaths;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.*;
import sh.zolt.cli.command.CommandServiceBundles.CommandTestServices;
import sh.zolt.cli.command.build.CommandBuildAttributes;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.test.TestSelection;
import sh.zolt.test.TestSelectionException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.service.WorkspaceTestResult;
import sh.zolt.workspace.service.WorkspaceTestService;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "integration-test",
        description = "Compile and run integration tests from configured integration-test roots.")
public final class IntegrationTestCommand implements Runnable {
    private final ZoltTomlParser tomlParser;
    private final TestRunService testRunService;
    private final WorkspaceTestService workspaceTestService;
    private final CommandServiceBundles.TestRunServiceFactory testRunServiceFactory;
    private final CommandLockfiles lockfiles;

    @Option(names = "--workspace", description = "Run integration tests for workspace members in dependency order.")
    private boolean workspace;

    @Option(names = "--all", description = "Select every workspace member.")
    private boolean all;

    @Option(names = "--member", description = "Select a workspace member by declared path. May be repeated.")
    private List<String> members = List.of();

    @Option(names = "--members", split = ",", description = "Select comma-separated workspace members by declared path.")
    private List<String> memberGroups = List.of();

    @Option(names = "--test", description = "Select one test class or method. May be repeated.")
    private List<String> testSelectors = List.of();

    @Option(names = "--tests", description = "Select test classes by glob-style class-name pattern. May be repeated.")
    private List<String> testPatterns = List.of();

    @Option(names = "--include-tag", description = "Include tests with a JUnit Platform tag. May be repeated.")
    private List<String> includedTags = List.of();

    @Option(names = "--exclude-tag", description = "Exclude tests with a JUnit Platform tag. May be repeated.")
    private List<String> excludedTags = List.of();

    @Option(names = "--jvm-arg", description = "Pass one JVM argument to the test runner process. May be repeated.")
    private List<String> jvmArgs = List.of();

    @Option(names = "--test-event", description = "Show JUnit test events: passed, skipped, or failed. May be repeated.")
    private List<String> testEvents = List.of();

    @Option(names = "--reports-dir", description = "Write JUnit XML reports to a project-relative directory.")
    private Path reportsDir;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Mixin
    private CommandToolchainOptions toolchainOptions = new CommandToolchainOptions();

    @Mixin
    private ZoltCli.TimingOptions timingOptions = new ZoltCli.TimingOptions();

    @Spec
    private CommandSpec spec;

    public IntegrationTestCommand() {
        this(
                new ZoltTomlParser(),
                CommandFrameworkServices.testCommandServices(),
                new CommandLockfiles());
    }

    IntegrationTestCommand(
            ZoltTomlParser tomlParser,
            CommandTestServices testServices,
            CommandLockfiles lockfiles) {
        this(
                tomlParser,
                testServices.testRunService(),
                testServices.workspaceTestService(),
                testServices.testRunServiceFactory(),
                lockfiles);
    }

    IntegrationTestCommand(
            ZoltTomlParser tomlParser,
            TestRunService testRunService,
            WorkspaceTestService workspaceTestService,
            CommandServiceBundles.TestRunServiceFactory testRunServiceFactory,
            CommandLockfiles lockfiles) {
        this.tomlParser = tomlParser;
        this.testRunService = testRunService;
        this.workspaceTestService = workspaceTestService;
        this.testRunServiceFactory = testRunServiceFactory;
        this.lockfiles = lockfiles;
    }

    @Override
    public void run() {
        TimingRecorder timings = CommandTimings.recorder(timingOptions);
        Path projectRoot = projectDirectory.path();
        try {
            TestSelection testSelection = TestSelection.fromCli(
                    testSelectors,
                    testPatterns,
                    includedTags,
                    excludedTags);
            TestJvmArguments testJvmArguments = TestJvmArguments.fromCli(jvmArgs);
            List<String> requestedTestEvents = CommandTestEvents.validated(testEvents);
            if (workspace) {
                runWorkspaceIntegrationTests(
                        projectRoot,
                        timings,
                        testSelection,
                        testJvmArguments,
                        TestReportSettings.reportsDirectory(workspaceReportsDir()),
                        requestedTestEvents);
                return;
            }
            runSingleProjectIntegrationTests(projectRoot, timings, testSelection, testJvmArguments, requestedTestEvents);
        } catch (BuildException
                | JavacException
                | GroovyCompileException
                | JavaRunException
                | ResourceCopyException
                | TestRunException
                | TestSelectionException
                | SourceDiscoveryException
                | sh.zolt.error.ActionableException
                | LockfileReadException
                | ResolveException
                | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        } finally {
            CommandTimings.print(spec, "integration-test", projectRoot, timingOptions, timings);
        }
    }

    private void runWorkspaceIntegrationTests(
            Path projectRoot,
            TimingRecorder timings,
            TestSelection testSelection,
            TestJvmArguments testJvmArguments,
            TestReportSettings reportSettings,
            List<String> requestedTestEvents) {
        WorkspaceTestService projectWorkspaceTestService = workspaceTestService.withMemberServices(
                toolchainOptions.workspaceJdkCheckers("integration-test"),
                toolchainOptions.workspaceIntegrationTestRunServices(testRunServiceFactory));
        lockfiles.requireFreshWorkspaceLockfile(projectRoot, cacheRoot, false);
        WorkspaceTestResult result = timings.measure(
                "integration-test workspace",
                () -> {
                    WorkspaceBuildPlan plan = timings.measure(
                            "plan workspace integration tests",
                            () -> projectWorkspaceTestService.planTests(
                                    projectRoot,
                                    cacheRoot,
                                    CommandWorkspaceSelections.from(all, members, memberGroups)),
                            CommandBuildAttributes::workspaceBuildPlan);
                    WorkspaceBuildResult buildResult = timings.measure(
                            "build workspace integration-test inputs",
                            () -> projectWorkspaceTestService.buildTestInputs(plan, cacheRoot),
                            build -> CommandBuildAttributes.workspaceBuild(build, plan.selection()));
                    return timings.measure(
                            "run workspace integration-test members",
                            () -> projectWorkspaceTestService.runIntegrationTests(
                                    plan,
                                    buildResult,
                                    cacheRoot,
                                    testSelection,
                                    testJvmArguments,
                                    reportSettings,
                                    requestedTestEvents),
                            CommandTestAttributes::workspaceTest);
                },
                CommandTestAttributes::workspaceTest);
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        for (WorkspaceTestResult.MemberTestRunResult member : result.members()) {
            CommandOutput.printAndFlush(spec, member.result().output());
            if (!member.result().output().isEmpty() && !member.result().output().endsWith("\n")) {
                output.blankLine();
            }
            output.success("Integration tests passed in " + member.member());
            member.result().reportsDirectory().ifPresent(directory ->
                    output.pointer("wrote", directory.toString()));
        }
        output.summary(
                "Integration tests passed for " + result.members().size() + " workspace members",
                result.members().size() + " members");
    }

    private void runSingleProjectIntegrationTests(
            Path projectRoot,
            TimingRecorder timings,
            TestSelection testSelection,
            TestJvmArguments testJvmArguments,
            List<String> requestedTestEvents) {
        ProjectConfig config = timings.measure(
                "config read",
                () -> tomlParser.parse(projectRoot.resolve("zolt.toml")));
        lockfiles.requireFreshLockfile(projectRoot, config, cacheRoot, false);
        ProjectConfig integrationConfig = config.withBuildSettings(config.build().asIntegrationTestBuild());
        var compileChecker = toolchainOptions.jdkChecker(projectRoot, integrationConfig, "integration-test");
        TestRunService projectTestRunService =
                testRunServiceFactory.create(
                        compileChecker,
                        toolchainOptions.testRuntimeRunChecker(projectRoot, integrationConfig, compileChecker));
        TestReportSettings reportSettings = TestReportSettings.reportsDirectory(integrationReportsDir(config));
        TestRunResult result = timings.measure(
                "run integration tests",
                () -> {
                    TestCompileResultWithClasspaths compileResult = timings.measure(
                            "compile integration tests",
                            () -> {
                                BuildResultWithClasspaths buildResult = timings.measure(
                                        "build integration-test inputs",
                                        () -> projectTestRunService.buildTestInputs(
                                                projectRoot,
                                                integrationConfig,
                                                cacheRoot),
                                        resultWithClasspaths -> CommandBuildAttributes.build(
                                                resultWithClasspaths.buildResult()));
                                TestCompileResult testCompileResult = timings.measure(
                                        "compile integration-test sources",
                                        () -> projectTestRunService.compileTests(
                                                projectRoot,
                                                integrationConfig,
                                                buildResult),
                                        CommandTestAttributes::testCompile);
                                return new TestCompileResultWithClasspaths(
                                        testCompileResult,
                                        buildResult.classpaths());
                            },
                            resultWithClasspaths -> CommandTestAttributes.testCompile(
                                    resultWithClasspaths.testCompileResult()));
                    return timings.measure(
                            "execute integration tests",
                            () -> projectTestRunService.runCompiledTests(
                                    projectRoot,
                                    integrationConfig,
                                    compileResult.classpaths(),
                                    compileResult.testCompileResult(),
                                    testSelection,
                                    testJvmArguments,
                                    reportSettings,
                                    requestedTestEvents),
                            CommandTestAttributes::testExecution);
                },
                CommandTestAttributes::testRun);
        CommandOutput.printAndFlush(spec, result.output());
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        if (!result.output().isEmpty() && !result.output().endsWith("\n")) {
            output.blankLine();
        }
        output.summary(
                "Integration tests passed",
                result.compileResult().sourceCount() + " test source files");
        result.reportsDirectory().ifPresent(directory ->
                output.pointer("wrote", directory.toString()));
    }

    private Path integrationReportsDir(ProjectConfig config) {
        return reportsDir == null
                ? Path.of(config.build().outputRoot()).resolve("integration-test-reports")
                : reportsDir;
    }

    private Path workspaceReportsDir() {
        return reportsDir == null ? Path.of("target/integration-test-reports") : reportsDir;
    }
}
