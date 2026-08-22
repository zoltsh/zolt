package sh.zolt.cli.command.testcmd;

import sh.zolt.build.BuildException;
import sh.zolt.build.BuildResultWithClasspaths;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.JavacException;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.build.testruntime.*;
import sh.zolt.build.testruntime.compile.TestCompileResult;
import sh.zolt.build.testruntime.compile.TestCompileResultWithClasspaths;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.CommandProgress;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.*;
import sh.zolt.cli.command.CommandServiceBundles.CommandTestServices;
import sh.zolt.cli.command.build.CommandBuildAttributes;
import sh.zolt.cli.command.testplan.TestPlanCommand;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.TestPlanException;
import sh.zolt.test.TestSelection;
import sh.zolt.test.TestSelectionException;
import sh.zolt.test.shard.TestShardException;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.service.*;
import sh.zolt.workspace.testpool.WorkspaceTestConcurrency;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.test.WorkspaceTestService;
import java.nio.file.Path;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "test", description = "Compile and run tests, starting with JUnit support.", subcommands = {TestPlanCommand.class})
public final class TestCommand implements Runnable {
    private final ManifestProjectLoader projectLoader;
    private final TestRunService testRunService;
    private final WorkspaceTestService workspaceTestService;
    private final CommandServiceBundles.TestRunServiceFactory testRunServiceFactory;
    private final CommandLockfiles lockfiles;

    @Option(names = "--workspace", description = "Test workspace members in dependency order.")
    private boolean workspace;

    @Option(names = "--all", description = "Select every workspace member.")
    private boolean all;

    @Option(names = "--member", description = "Select a workspace member by declared path. May be repeated.")
    private List<String> members = List.of();

    @Option(names = "--members", split = ",", description = "Select comma-separated workspace members by declared path.")
    private List<String> memberGroups = List.of();

    @Option(names = "--suite", description = "Run one configured test suite. Defaults to all.")
    private String suiteName = "all";

    @Option(names = "--shard", description = "Run one deterministic test shard as index/total, such as 1/4.")
    private String shardValue;

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

    @Option(names = "--test-workers", description = "Run this many workspace members at once. Defaults to scaling with the machine.")
    private String testWorkers;

    @Option(names = "--test-event", description = "Show JUnit test events: passed, skipped, or failed. May be repeated.")
    private List<String> testEvents = List.of();

    @Option(names = "--reports-dir", description = "Write JUnit XML reports to a project-relative directory.")
    private Path reportsDir;

    @Option(names = "--compile-only", description = "Compile test sources without running tests.")
    private boolean compileOnly;

    @Mixin
    private CommandTestProfileOptions profileOptions = new CommandTestProfileOptions();

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Option(
            names = "--no-build-cache",
            description = "Bypass the build-output cache for this run (neither restore nor store).")
    private boolean noBuildCache;

    @Mixin
    private CommandToolchainOptions toolchainOptions = new CommandToolchainOptions();

    @Mixin
    private ZoltCli.TimingOptions timingOptions = new ZoltCli.TimingOptions();

    @Spec
    private CommandSpec spec;

    public TestCommand() {
        this(new ManifestProjectLoader(), CommandFrameworkServices.testCommandServices(), new CommandLockfiles());
    }

    TestCommand(
            ManifestProjectLoader projectLoader,
            CommandTestServices testServices,
            CommandLockfiles lockfiles) {
        this(
                projectLoader,
                testServices.testRunService(),
                testServices.workspaceTestService(),
                testServices.testRunServiceFactory(),
                lockfiles);
    }

    TestCommand(
            ManifestProjectLoader projectLoader,
            TestRunService testRunService,
            WorkspaceTestService workspaceTestService,
            CommandServiceBundles.TestRunServiceFactory testRunServiceFactory,
            CommandLockfiles lockfiles) {
        this.projectLoader = projectLoader;
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
            TestCommandRequest request = new TestCommandRequest(
                    TestSelection.fromCli(testSelectors, testPatterns, includedTags, excludedTags),
                    TestJvmArguments.fromCli(jvmArgs),
                    TestReportSettings.reportsDirectory(reportsDir),
                    profileOptions.settings(),
                    CommandTestEvents.validated(testEvents),
                    suiteName,
                    TestShardSpec.parse(shardValue),
                    WorkspaceTestConcurrency.fromCli(testWorkers));
            if (workspace) {
                runWorkspace(
                        projectRoot,
                        CommandWorkspaceSelections.from(all, members, memberGroups),
                        timings,
                        request);
                return;
            }
            ProjectCommandContext context = timings.measure(
                    "config read",
                    () -> ProjectCommandContext.load(projectLoader, projectRoot));
            if (context.workspaceMember()) {
                // Design §4.5: the member's test lane is a projection of the workspace resolution, and
                // its providers must be built first, so the workspace service owns both paths.
                runWorkspace(context.lockRoot(), context.memberSelection(), timings, request);
                return;
            }
            if (compileOnly) {
                compileRunner().compileSingle(
                        context, cacheRoot, noBuildCache, timings, CommandProgress.human(spec));
            } else {
                runSingleProjectTests(context, timings, CommandProgress.human(spec), request);
            }
        } catch (BuildException
                | JavacException
                | GroovyCompileException
                | JavaRunException
                | ResourceCopyException
                | TestRunException
                | TestSelectionException
                | TestShardException
                | TestPlanException
                | SourceDiscoveryException
                | sh.zolt.error.ActionableException
                | LockfileReadException
                | ResolveException
                | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        } finally {
            CommandTimings.print(spec, "test", projectRoot, timingOptions, timings);
        }
    }

    /**
     * The workspace path, whether {@code --workspace} named the members or a member directory did.
     */
    private void runWorkspace(
            Path workspaceRoot,
            WorkspaceSelectionRequest selection,
            TimingRecorder timings,
            TestCommandRequest request) {
        if (compileOnly) {
            compileRunner().compileWorkspace(
                    workspaceRoot, cacheRoot, selection, timings, CommandProgress.human(spec));
            return;
        }
        new WorkspaceTestCommandRunner(
                        workspaceTestService, testRunServiceFactory, lockfiles, toolchainOptions, spec)
                .runTests(
                        workspaceRoot, cacheRoot, selection, timings, CommandProgress.human(spec), request);
    }

    private void runSingleProjectTests(
            ProjectCommandContext context,
            TimingRecorder timings,
            ProgressWriter progress,
            TestCommandRequest request) {
        Path projectRoot = context.projectRoot();
        ProjectConfig config = context.config();
        var compileChecker = toolchainOptions.jdkChecker(projectRoot, config, "test");
        TestRunService projectTestRunService =
                testRunServiceFactory.create(
                                compileChecker,
                                toolchainOptions.testRuntimeRunChecker(projectRoot, config, compileChecker))
                        .withBuildCache(CommandBuildCache.service(noBuildCache, false));
        var artifactIndex = lockfiles.requireFreshLockfile(context, cacheRoot, false);
        progress.start("Testing project");
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        output.work("Testing " + config.project().name());
        TestRunResult result = timings.measure(
                "run tests",
                () -> {
                    TestCompileResultWithClasspaths compileResult = timings.measure(
                            "compile tests",
                            () -> {
                                BuildResultWithClasspaths buildResult = timings.measure(
                                        "build test inputs",
                                        () -> projectTestRunService.buildTestInputs(
                                                projectRoot,
                                                config,
                                                cacheRoot,
                                                artifactIndex),
                                        resultWithClasspaths -> CommandBuildAttributes.build(
                                                resultWithClasspaths.buildResult()));
                                TestCompileResult testCompileResult = timings.measure(
                                        "compile test sources",
                                        () -> projectTestRunService.compileTests(
                                                projectRoot,
                                                config,
                                                buildResult),
                                        CommandTestAttributes::testCompile);
                                return new TestCompileResultWithClasspaths(
                                        testCompileResult,
                                        buildResult.classpaths());
                            },
                            resultWithClasspaths -> CommandTestAttributes.testCompile(
                                    resultWithClasspaths.testCompileResult()));
                    return timings.measure(
                            "execute tests",
                            () -> projectTestRunService.runCompiledTests(
                                    projectRoot,
                                    config,
                                    compileResult.classpaths(),
                                    compileResult.testCompileResult(),
                                    request.testSelection(),
                                    request.testJvmArguments(),
                                    request.reportSettings(),
                                    request.requestedTestEvents(),
                                    request.suiteName(),
                                    request.shard(),
                                    request.profileSettings()),
                            CommandTestAttributes::testExecution);
                },
                CommandTestAttributes::testRun);
        CommandOutput.printAndFlush(spec, result.output());
        if (!result.output().isEmpty() && !result.output().endsWith("\n")) {
            output.blankLine();
        }
        output.summary(
                "Tests passed",
                result.compileResult().sourceCount() + " test source files");
        result.reportsDirectory().ifPresent(directory ->
                output.pointer("wrote", directory.toString()));
        CommandTestProfileOutput.print(output, result, request.profileSettings());
        output.provenance(CommandBuildProvenance.read(projectRoot));
        progress.result("Tested project");
    }

    private TestCompileCommandRunner compileRunner() {
        return new TestCompileCommandRunner(
                workspaceTestService, testRunServiceFactory, lockfiles, toolchainOptions, spec);
    }
}
