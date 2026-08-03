package sh.zolt.cli.command.quality;

import sh.zolt.build.BuildException;
import sh.zolt.build.CoverageException;
import sh.zolt.build.coverage.CoverageReportSettings;
import sh.zolt.build.coverage.CoverageResult;
import sh.zolt.build.coverage.CoverageService;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.JavaRunException;
import sh.zolt.build.JavacException;
import sh.zolt.build.ResourceCopyException;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.*;
import sh.zolt.cli.command.CommandServiceBundles.CommandCoverageServices;
import sh.zolt.cli.command.testcmd.CommandTestAttributes;
import sh.zolt.cli.command.testcmd.CommandTestEvents;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.project.CoverageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.test.TestSelection;
import sh.zolt.test.TestSelectionException;
import sh.zolt.test.shard.TestShardException;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.coverage.WorkspaceCoverageResult;
import sh.zolt.workspace.coverage.WorkspaceCoverageService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "coverage",
        description = "Run tests with Jacoco instrumentation and write coverage reports.")
public final class CoverageCommand implements Runnable {
    private final ZoltTomlParser tomlParser;
    private final CoverageService coverageService;
    private final WorkspaceCoverageService workspaceCoverageService;
    private final CommandServiceBundles.CoverageServiceFactory coverageServiceFactory;
    private final CommandServiceBundles.TestRunServiceFactory testRunServiceFactory;

    @Option(names = "--workspace", description = "Run coverage for workspace members and write aggregate reports.")
    private boolean workspace;

    @Option(names = "--all", description = "Select every workspace member.")
    private boolean all;

    @Option(names = "--member", description = "Select a workspace member by declared path. May be repeated.")
    private List<String> members = List.of();

    @Option(names = "--members", split = ",", description = "Select comma-separated workspace members by declared path.")
    private List<String> memberGroups = List.of();

    @Option(names = "--suite", description = "Run coverage for one configured test suite. Defaults to all.")
    private String suiteName = "all";

    @Option(names = "--shard", description = "Run coverage for one deterministic test shard as index/total, such as 1/4.")
    private String shardValue;

    @Option(names = "--test", description = "Select one test class or method. May be repeated.")
    private List<String> testSelectors = List.of();

    @Option(names = "--tests", description = "Select test classes by glob-style class-name pattern. May be repeated.")
    private List<String> testPatterns = List.of();

    @Option(names = "--include-tag", description = "Include tests with a JUnit Platform tag. May be repeated.")
    private List<String> includedTags = List.of();

    @Option(names = "--exclude-tag", description = "Exclude tests with a JUnit Platform tag. May be repeated.")
    private List<String> excludedTags = List.of();

    @Option(names = "--test-event", description = "Show JUnit test events: passed, skipped, or failed. May be repeated.")
    private List<String> testEvents = List.of();

    @Option(names = "--no-xml", description = "Disable the Jacoco XML report.")
    private boolean noXml;

    @Option(names = "--no-html", description = "Disable the Jacoco HTML report.")
    private boolean noHtml;

    @Option(names = "--exec-file", description = "Project-relative Jacoco execution data path.")
    private Path execFile;

    @Option(names = "--xml-report", description = "Project-relative Jacoco XML report path.")
    private Path xmlReport;

    @Option(names = "--html-dir", description = "Project-relative Jacoco HTML report directory.")
    private Path htmlDirectory;

    @Option(names = "--reports-dir", description = "Write JUnit XML reports to a project-relative directory.")
    private Path reportsDir;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Mixin
    private CommandToolchainOptions toolchainOptions = new CommandToolchainOptions();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Mixin
    private ZoltCli.TimingOptions timingOptions = new ZoltCli.TimingOptions();

    @Spec
    private CommandSpec spec;

    public CoverageCommand() {
        this(CommandFrameworkServices.coverageCommandServices());
    }

    private CoverageCommand(CommandCoverageServices services) {
        this(
                services.tomlParser(),
                services.coverageService(),
                services.workspaceCoverageService(),
                services.coverageServiceFactory(),
                services.testRunServiceFactory());
    }

    CoverageCommand(
            ZoltTomlParser tomlParser,
            CoverageService coverageService,
            WorkspaceCoverageService workspaceCoverageService,
            CommandServiceBundles.CoverageServiceFactory coverageServiceFactory,
            CommandServiceBundles.TestRunServiceFactory testRunServiceFactory) {
        this.tomlParser = tomlParser;
        this.coverageService = coverageService;
        this.workspaceCoverageService = workspaceCoverageService;
        this.coverageServiceFactory = coverageServiceFactory;
        this.testRunServiceFactory = testRunServiceFactory;
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
            TestShardSpec shard = TestShardSpec.parse(shardValue);
            List<String> requestedTestEvents = CommandTestEvents.validated(testEvents);
            if (workspace) {
                runWorkspaceCoverage(projectRoot, timings, testSelection, coverageReportSettings(Path.of("target")), requestedTestEvents, suiteName, shard);
                return;
            }
            ProjectConfig config = timings.measure(
                    "config read",
                    () -> tomlParser.parse(projectRoot.resolve("zolt.toml")));
            var compileChecker = toolchainOptions.jdkChecker(projectRoot, config, "coverage");
            CoverageService projectCoverageService = coverageServiceFactory.create(
                    compileChecker,
                    toolchainOptions.testRuntimeRunChecker(projectRoot, config, compileChecker));
            CoverageReportSettings reportSettings = coverageReportSettings(Path.of(config.build().outputRoot()));
            CoverageResult result = timings.measure(
                    "run coverage",
                    () -> projectCoverageService.runCoverage(
                            projectRoot,
                            config,
                            cacheRoot,
                            testSelection,
                            reportSettings,
                            requestedTestEvents,
                            suiteName,
                            shard),
                    coverageResult -> Map.of(
                            CommandAttributeKeys.EXEC_FILE, coverageResult.execFile().toString(),
                            CommandAttributeKeys.XML_REPORT, coverageResult.xmlReport().map(Path::toString).orElse("disabled"),
                            CommandAttributeKeys.HTML_DIRECTORY, coverageResult.htmlDirectory().map(Path::toString).orElse("disabled")));
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            CommandOutput.printAndFlush(spec, result.testRunResult().output());
            if (!result.testRunResult().output().isEmpty() && !result.testRunResult().output().endsWith("\n")) {
                CommandOutput.printAndFlush(spec, System.lineSeparator());
            }
            if (!result.reportOutput().isBlank()) {
                CommandOutput.printAndFlush(spec, result.reportOutput());
                if (!result.reportOutput().endsWith("\n")) {
                    CommandOutput.printAndFlush(spec, System.lineSeparator());
                }
            }
            printCoverageReports(output, "Coverage reports written", result.execFile(), result.xmlReport(), result.htmlDirectory());
            result.testRunResult().reportsDirectory().ifPresent(path -> output.pointer("wrote", path.toString()));
            CoverageFloorEnforcement.enforce(
                    spec,
                    tomlParser.parseCoverageFloors(projectRoot.resolve("zolt.toml")),
                    result.xmlReport());
        } catch (BuildException
                | CoverageException
                | JavacException
                | GroovyCompileException
                | JavaRunException
                | ResourceCopyException
                | TestRunException
                | TestSelectionException
                | TestShardException
                | SourceDiscoveryException
                | LockfileReadException
                | ResolveException
                | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        } finally {
            CommandTimings.print(spec, "coverage", projectRoot, timingOptions, timings);
        }
    }

    private CoverageReportSettings coverageReportSettings(Path outputRoot) {
        return CoverageReportSettings.forOutputRoot(
                !noXml,
                !noHtml,
                outputRoot,
                execFile,
                xmlReport,
                htmlDirectory,
                reportsDir);
    }

    private void runWorkspaceCoverage(
            Path projectRoot,
            TimingRecorder timings,
            TestSelection testSelection,
            CoverageReportSettings reportSettings,
            List<String> requestedTestEvents,
            String suiteName,
            TestShardSpec shard) {
        CommandToolchainOptions.WorkspaceCommandToolchains workspaceToolchains =
                toolchainOptions.workspaceCoverageToolchains(
                        testRunServiceFactory);
        WorkspaceCoverageService projectWorkspaceCoverageService =
                workspaceCoverageService.withMemberServices(
                        workspaceToolchains.mainCheckers(),
                        workspaceToolchains.testRunServices());
        WorkspaceCoverageResult result = timings.measure(
                "workspace coverage",
                () -> projectWorkspaceCoverageService.runCoverage(
                        projectRoot,
                        cacheRoot,
                        CommandWorkspaceSelections.from(all, members, memberGroups),
                        testSelection,
                        reportSettings,
                        requestedTestEvents,
                        suiteName,
                        shard),
                coverageResult -> {
                    Map<String, String> attributes = new java.util.LinkedHashMap<>(
                            CommandTestAttributes.workspaceTest(coverageResult.testResult()));
                    attributes.put(CommandAttributeKeys.EXEC_FILE, coverageResult.execFile().toString());
                    attributes.put(CommandAttributeKeys.XML_REPORT, coverageResult.xmlReport().map(Path::toString).orElse("disabled"));
                    attributes.put(CommandAttributeKeys.HTML_DIRECTORY, coverageResult.htmlDirectory().map(Path::toString).orElse("disabled"));
                    return attributes;
                });
        CommandHumanOutput output = CommandHumanOutput.of(spec);
        for (WorkspaceCoverageResult.MemberCoverageRunResult member : result.members()) {
            CommandOutput.printAndFlush(spec, member.result().output());
            if (!member.result().output().isEmpty() && !member.result().output().endsWith("\n")) {
                CommandOutput.printAndFlush(spec, System.lineSeparator());
            }
            output.success("Coverage tests passed in " + member.member());
            member.result().reportsDirectory().ifPresent(directory ->
                    output.pointer("wrote", directory.toString()));
        }
        if (!result.reportOutput().isBlank()) {
            CommandOutput.printAndFlush(spec, result.reportOutput());
            if (!result.reportOutput().endsWith("\n")) {
                CommandOutput.printAndFlush(spec, System.lineSeparator());
            }
        }
        printCoverageReports(output, "Workspace coverage reports written", result.execFile(), result.xmlReport(), result.htmlDirectory());
        CoverageFloorEnforcement.enforce(
                spec,
                result.coverageSettings(),
                result.xmlReport());
        output.summary(
                "Coverage passed for " + result.members().size() + " workspace members",
                result.members().size() + " members");
    }

    private static void printCoverageReports(
            CommandHumanOutput output,
            String summary,
            Path execFile,
            Optional<Path> xmlReport,
            Optional<Path> htmlDirectory) {
        int reportCount = 1 + (xmlReport.isPresent() ? 1 : 0) + (htmlDirectory.isPresent() ? 1 : 0);
        output.summary(summary, reportCount + (reportCount == 1 ? " report" : " reports"));
        output.pointer("wrote", execFile.toString());
        xmlReport.ifPresent(path -> output.pointer("wrote", path.toString()));
        htmlDirectory.ifPresent(path -> output.pointer("wrote", path.toString()));
    }
}
