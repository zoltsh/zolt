package sh.zolt.cli.command.quality;

import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandProjectLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.policy.DependencyPolicyReport;
import sh.zolt.policy.DependencyPolicyReportException;
import sh.zolt.policy.DependencyPolicyReportFormatter;
import sh.zolt.policy.DependencyPolicyReportService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.ManifestProject;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "policy", description = "Show dependency baseline and policy diagnostics.")
public final class PolicyCommand implements Runnable {
    private final ManifestProjectLoader projectLoader;
    private final ZoltLockfileReader lockfileReader;
    private final DependencyPolicyReportService reportService;
    private final DependencyPolicyReportFormatter reportFormatter;

    enum Format {
        TEXT,
        JSON
    }

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--format", description = "Output format: text or json.")
    private Format format = Format.TEXT;

    @Spec
    private CommandSpec spec;

    public PolicyCommand() {
        this(
                new ManifestProjectLoader(),
                new ZoltLockfileReader(),
                new DependencyPolicyReportService(),
                new DependencyPolicyReportFormatter());
    }

    PolicyCommand(
            ManifestProjectLoader projectLoader,
            ZoltLockfileReader lockfileReader,
            DependencyPolicyReportService reportService,
            DependencyPolicyReportFormatter reportFormatter) {
        this.projectLoader = projectLoader;
        this.lockfileReader = lockfileReader;
        this.reportService = reportService;
        this.reportFormatter = reportFormatter;
    }

    @Override
    public void run() {
        try {
            Path projectRoot = projectDirectory.path();
            ManifestProject project = projectLoader.project(projectRoot);
            ProjectConfig config = project.config();
            ZoltLockfile lockfile = lockfileReader.read(CommandProjectLockfile.path(project));
            DependencyPolicyReport report = reportService.report(
                    projectRoot,
                    config,
                    lockfile);
            CommandOutput.printAndFlush(
                    spec,
                    format == Format.JSON ? reportFormatter.json(report) : reportFormatter.text(report));
        } catch (DependencyPolicyReportException
                | LockfileReadException
                | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }
}
