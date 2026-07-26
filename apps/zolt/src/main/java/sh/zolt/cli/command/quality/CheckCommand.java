package sh.zolt.cli.command.quality;

import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.CommandAttributeKeys;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandTimings;
import sh.zolt.cli.command.CommandWorkspaceSelections;
import sh.zolt.cli.command.CommandServiceBundles.CommandQualityServices;
import sh.zolt.perf.TimingRecorder;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckFormatter;
import sh.zolt.quality.QualityCheckReport;
import sh.zolt.quality.QualityCheckRequest;
import sh.zolt.quality.QualityCheckService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "check", description = "Run Zolt-owned quality checks.")
public final class CheckCommand implements Callable<Integer> {
    private final QualityCheckService qualityCheckService;

    enum Format {
        TEXT,
        JSON
    }

    @Option(names = "--check", description = "Run a quality check id. May be repeated.")
    private List<String> checks = List.of();

    @Option(names = "--context", description = "Apply a built-in check context. Supported values: local, ci.")
    private QualityCheckContext context;

    @Option(names = "--reports-dir", description = "Validate project-relative JUnit XML report output for CI context.")
    private Path reportsDir;

    @Option(names = "--coverage-dir", description = "Validate project-relative coverage report output for CI context.")
    private Path coverageDir;

    @Option(names = "--require-package", description = "Require the configured package artifact and package evidence during CI context checks.")
    private boolean requirePackage;

    @Option(names = "--require-publish-dry-run", description = "Require publish dry-run preflight during CI context checks without uploading.")
    private boolean requirePublishDryRun;

    @Option(names = "--require-offline-ready", description = "Require locked dependency metadata to be available from the local cache during CI context checks.")
    private boolean requireOfflineReady;

    @Option(names = "--workspace", description = "Check workspace members using the workspace selection model.")
    private boolean workspace;

    @Option(names = "--offline", description = "Use only artifacts already present in the local cache for checks that need dependency metadata.")
    private boolean offline;

    @Option(names = "--all", description = "Select every workspace member.")
    private boolean all;

    @Option(names = "--member", description = "Select a workspace member by declared path. May be repeated.")
    private List<String> members = List.of();

    @Option(names = "--members", split = ",", description = "Select comma-separated workspace members by declared path.")
    private List<String> memberGroups = List.of();

    @Option(names = "--format", description = "Output format: text or json.")
    private Format format = Format.TEXT;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Mixin
    private ZoltCli.TimingOptions timingOptions = new ZoltCli.TimingOptions();

    @Spec
    private CommandSpec spec;

    public CheckCommand() {
        this(CommandFrameworkServices.qualityCommandServices());
    }

    private CheckCommand(CommandQualityServices services) {
        this(services.qualityCheckService());
    }

    CheckCommand(QualityCheckService qualityCheckService) {
        this.qualityCheckService = qualityCheckService;
    }

    @Override
    public Integer call() {
        TimingRecorder timings = CommandTimings.recorder(timingOptions);
        Path projectRoot = projectDirectory.path();
        QualityCheckReport report = timings.measure(
                "run quality checks",
                () -> qualityCheckService.check(new QualityCheckRequest(
                        projectRoot,
                        cacheRoot,
                        offline,
                        workspace,
                        checks,
                        context,
                        reportsDir,
                        coverageDir,
                        requirePackage,
                        requirePublishDryRun,
                        requireOfflineReady,
                        CommandWorkspaceSelections.from(all, members, memberGroups))),
                CheckCommand::qualityCheckAttributes);
        if (format == Format.JSON) {
            CommandOutput.printAndFlush(spec, QualityCheckFormatter.json(report));
        } else {
            CommandCheckOutput.print(spec, report);
        }
        CommandTimings.print(spec, "check", projectRoot, timingOptions, timings);
        return report.ok() ? 0 : 1;
    }

    private static Map<String, String> qualityCheckAttributes(QualityCheckReport result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(CommandAttributeKeys.CHECKS, Integer.toString(result.checks().size()));
        attributes.put(CommandAttributeKeys.PASSED, Long.toString(result.passedCount()));
        attributes.put(CommandAttributeKeys.FAILED, Long.toString(result.failedCount()));
        attributes.put(CommandAttributeKeys.SKIPPED, Long.toString(result.skippedCount()));
        attributes.put(CommandAttributeKeys.WORKSPACE, Boolean.toString(result.workspace()));
        attributes.put(CommandAttributeKeys.OK, Boolean.toString(result.ok()));
        return attributes;
    }
}
