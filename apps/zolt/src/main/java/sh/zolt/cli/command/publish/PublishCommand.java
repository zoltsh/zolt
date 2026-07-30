package sh.zolt.cli.command.publish;

import sh.zolt.build.PackageException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandProgress;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandLockfiles;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandWorkspaceSelections;
import sh.zolt.cli.console.ProgressWriter;
import sh.zolt.cli.net.CommandNetwork;
import sh.zolt.publish.CentralPortalClient;
import sh.zolt.publish.PublishCentralBundleResult;
import sh.zolt.publish.PublishCentralPublishOutcome;
import sh.zolt.publish.PublishCentralPublishService;
import sh.zolt.publish.PublishCentralReadinessService;
import sh.zolt.publish.PublishCentralRequirement;
import sh.zolt.publish.PublishCentralUploadFormatter;
import sh.zolt.publish.PublishCentralUploadResult;
import sh.zolt.publish.PublishContext;
import sh.zolt.publish.PublishDryRunFormatter;
import sh.zolt.publish.PublishDryRunPlan;
import sh.zolt.publish.PublishDryRunService;
import sh.zolt.publish.PublishException;
import sh.zolt.publish.PublishReleasePolicyService;
import sh.zolt.publish.PublishUploadFormatter;
import sh.zolt.publish.PublishUploadResult;
import sh.zolt.publish.PublishUploadService;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.cli.ZoltCli;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.publish.WorkspacePublishReport;
import sh.zolt.workspace.publish.WorkspacePublishService;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
        name = "publish",
        description = "Publish Zolt-produced artifacts to Maven-compatible repositories.")
public final class PublishCommand implements Callable<Integer> {
    private final PublishDryRunService dryRunService;
    private final PublishReleasePolicyService releasePolicyService;
    private final PublishUploadService uploadService;
    private final PublishCentralReadinessService centralReadinessService;
    private final PublishCentralPublishService centralPublishService;
    private final WorkspacePublishService workspacePublishService;
    private final CommandLockfiles lockfiles;
    private final PublishSbomArtifactGenerator sbomGenerator = new PublishSbomArtifactGenerator();

    @Option(names = "--workspace", description = "Publish workspace members (and their BOM) as one family in dependency order.")
    private boolean workspace;

    @Option(names = "--all", description = "Select every workspace member.")
    private boolean all;

    @Option(names = "--member", description = "Select a workspace member by declared path. May be repeated.")
    private List<String> members = List.of();

    @Option(names = "--members", split = ",", description = "Select comma-separated workspace members by declared path.")
    private List<String> memberGroups = List.of();

    @Option(names = "--resume-members", split = ",", hidden = true,
            description = "Resume a plain-repository publish for these EXACT members (no dependency expansion).")
    private List<String> resumeMembers = List.of();

    @Option(names = "--allow-mixed-versions",
            description = "Allow workspace family members to publish at divergent versions (default: require a uniform version).")
    private boolean allowMixedVersions;

    @Option(names = "--offline", description = "Use only artifacts already present in the local cache.")
    private boolean offline;

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Option(names = "--dry-run", description = "Preview target routing, artifact evidence, and blockers without uploading.")
    private boolean dryRun;

    @Option(names = "--sbom", description = "Attach a CycloneDX SBOM (classifier cyclonedx, extension json) to the publish.")
    private boolean sbom;

    @Option(names = "--central", description = "Target Maven Central: publish a signed bundle, or with --dry-run report readiness and assemble the bundle locally.")
    private boolean central;

    @Option(names = "--context", description = "Apply a publish context policy. Supported values: release.")
    private PublishContext context;

    @Option(names = "--wait", description = "After a --central upload, poll the deployment until it reaches a terminal state (published, failed, or — for user-managed — validated).")
    private boolean wait;

    @Option(names = "--wait-timeout", paramLabel = "<seconds>", defaultValue = "300",
            description = "Maximum seconds to wait for a terminal state when --wait is set (default: 300).")
    private long waitTimeoutSeconds;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Spec
    private CommandSpec spec;

    public PublishCommand() {
        this(sh.zolt.cli.command.CommandFrameworkServices.publishCommandServices());
    }

    private PublishCommand(
            sh.zolt.cli.command.CommandServiceBundles.CommandPublishServices services) {
        this(
                services.publishDryRunService(),
                new PublishReleasePolicyService(),
                new PublishUploadService(CommandNetwork.repositoryClient()),
                new PublishCentralReadinessService(),
                new PublishCentralPublishService(new CentralPortalClient(CommandNetwork.defaultTransport())),
                services.workspacePublishService(),
                new CommandLockfiles());
    }

    PublishCommand(
            PublishDryRunService dryRunService,
            PublishReleasePolicyService releasePolicyService,
            PublishUploadService uploadService,
            PublishCentralReadinessService centralReadinessService,
            PublishCentralPublishService centralPublishService,
            WorkspacePublishService workspacePublishService,
            CommandLockfiles lockfiles) {
        this.dryRunService = dryRunService;
        this.releasePolicyService = releasePolicyService;
        this.uploadService = uploadService;
        this.centralReadinessService = centralReadinessService;
        this.centralPublishService = centralPublishService;
        this.workspacePublishService = workspacePublishService;
        this.lockfiles = lockfiles;
    }

    @Override
    public Integer call() {
        ProgressWriter progress = CommandProgress.human(spec);
        Path projectRoot = projectDirectory.path();
        try {
            if (context != null && !dryRun) {
                CommandFailures.printUser(spec, "Publish context policy is currently supported only with --dry-run.");
                return 1;
            }
            if (wait && (dryRun || !central)) {
                CommandFailures.printUser(spec,
                        "The --wait flag applies only to a live Maven Central publish; use it with --central and without --dry-run.");
                return 1;
            }
            if (wait && waitTimeoutSeconds <= 0) {
                CommandFailures.printUser(spec, "--wait-timeout must be a positive number of seconds.");
                return 1;
            }
            if (workspace) {
                return runWorkspacePublish(projectRoot);
            }
            // A workspace member has no member-level zolt.lock, so a Central dry run there plans through
            // the workspace planner against the aggregated root lock (and brings its own member SBOM).
            // It therefore takes the same workspace lock freshness gate and the same --offline handling as
            // `publish --workspace`; both entry paths must give identical dependency guarantees.
            PublishMemberDryRun memberDryRun = PublishMemberDryRun.resolve(
                    dryRun && central,
                    workspacePublishService,
                    lockfiles,
                    projectRoot,
                    cacheRoot,
                    offline,
                    sbomGenerator.memberGenerator(sbom, ZoltCli.version()));
            Optional<Path> sbomFile = memberDryRun.present() ? Optional.empty() : generateSbom(projectRoot);
            if (central && !dryRun) {
                progress.start("Publishing to Maven Central");
                PublishDryRunPlan plan =
                        dryRunService.plan(
                                projectRoot,
                                false,
                                sbomFile,
                                cacheRoot);
                if (!plan.ok()) {
                    CommandOutput.printAndFlush(spec, PublishDryRunFormatter.text(plan));
                    return 1;
                }
                List<PublishCentralRequirement> readiness = centralReadinessService.evaluate(projectRoot, plan);
                if (!readiness.stream().allMatch(PublishCentralRequirement::satisfied)) {
                    CommandOutput.printAndFlush(spec, PublishDryRunFormatter.centralReadiness(readiness));
                    return 1;
                }
                Optional<Duration> waitTimeout = wait
                        ? Optional.of(Duration.ofSeconds(waitTimeoutSeconds))
                        : Optional.empty();
                PublishCentralUploadResult centralResult = centralPublishService.publish(projectRoot, plan, waitTimeout);
                CommandOutput.printAndFlush(spec, PublishCentralUploadFormatter.text(centralResult));
                progress.result(centralProgressResult(centralResult.outcome()));
                return 0;
            }
            if (dryRun) {
                progress.start("Preparing publish dry run");
                PublishDryRunPlan planned =
                        memberDryRun.plan(() -> dryRunService.plan(projectRoot, !central, sbomFile, cacheRoot));
                PublishDryRunPlan plan = context == PublishContext.RELEASE
                        ? releasePolicyService.apply(projectRoot, planned)
                        : planned;
                StringBuilder output = new StringBuilder(PublishDryRunFormatter.text(plan));
                boolean centralReady = true;
                if (central) {
                    List<PublishCentralRequirement> readiness =
                            memberDryRun.readiness(centralReadinessService, projectRoot, plan);
                    output.append(PublishDryRunFormatter.centralReadiness(readiness));
                    centralReady = readiness.stream().allMatch(PublishCentralRequirement::satisfied);
                    if (plan.ok()) {
                        Path bundleRoot = memberDryRun.root(projectRoot);
                        PublishCentralBundleResult bundle = centralPublishService.assembleBundle(bundleRoot, plan);
                        output.append(PublishDryRunFormatter.centralBundle(
                                displayPath(bundleRoot, bundle.bundlePath()), bundle.entries()));
                    }
                }
                CommandOutput.printAndFlush(spec, output.toString());
                progress.result("Prepared publish dry run");
                return plan.ok() && centralReady ? 0 : 1;
            }
            progress.start("Publishing artifacts");
            PublishUploadResult result =
                    uploadService.upload(
                            projectRoot,
                            sbomFile,
                            cacheRoot);
            CommandOutput.printAndFlush(spec, PublishUploadFormatter.text(result));
            progress.result("Published artifacts");
            return 0;
        } catch (PublishException | ZoltConfigException | PackageException | LockfileReadException
                | WorkspaceConfigException | sh.zolt.error.ActionableException exception) {
            CommandFailures.printUser(spec, exception);
            return 1;
        } catch (UncheckedIOException exception) {
            CommandFailures.printUser(spec, "Could not write the SBOM artifact for publishing: " + exception.getMessage());
            return 1;
        }
    }

    private Integer runWorkspacePublish(Path projectRoot) {
        if (context != null) {
            CommandFailures.printUser(spec, "Publish context policy is not yet supported with --workspace.");
            return 1;
        }
        if (!resumeMembers.isEmpty() && (all || !members.isEmpty() || !memberGroups.isEmpty())) {
            CommandFailures.printUser(spec,
                    "--resume-members selects members exactly; do not combine it with --all, --member, or --members.");
            return 1;
        }
        return sh.zolt.workspace.service.WorkspaceMutationLock.withWorkspaceLock(projectRoot, () -> {
            ProgressWriter progress = CommandProgress.human(spec);
            lockfiles.requireFreshWorkspaceLockfile(
                    projectRoot,
                    cacheRoot,
                    offline,
                    "zolt publish --workspace");
            WorkspaceSelectionRequest selection =
                    CommandWorkspaceSelections.from(all, members, memberGroups, resumeMembers);
            Optional<Duration> waitTimeout =
                    wait ? Optional.of(Duration.ofSeconds(waitTimeoutSeconds)) : Optional.empty();
            WorkspacePublishService.Options options =
                    new WorkspacePublishService.Options(dryRun, central, allowMixedVersions, sbom, waitTimeout);
            progress.start(dryRun ? "Preparing workspace publish" : "Publishing workspace family");
            WorkspacePublishReport report = workspacePublishService.publish(
                    projectRoot,
                    cacheRoot,
                    selection,
                    options,
                    sbomGenerator.memberGenerator(sbom, ZoltCli.version()));
            CommandOutput.printAndFlush(spec, formatWorkspaceReport(report));
            if (!report.ok()) {
                return 1;
            }
            progress.result(report.uploaded() ? "Published workspace family" : "Prepared workspace publish");
            return 0;
        });
    }

    private static String formatWorkspaceReport(WorkspacePublishReport report) {
        StringBuilder output = new StringBuilder();
        output.append("Workspace publish family (").append(report.members().size()).append(" member(s)):\n");
        for (WorkspacePublishReport.Member member : report.members()) {
            output.append("- ").append(member.coordinate());
            if (member.bom()) {
                output.append(" [bom]");
            }
            output.append(" -> ").append(member.plan().repositoryId()).append('\n');
        }
        if (!report.blockers().isEmpty()) {
            output.append("Blockers:\n");
            for (String blocker : report.blockers()) {
                output.append("- ").append(blocker).append('\n');
            }
        }
        if (!report.notes().isEmpty()) {
            output.append("Notes:\n");
            for (String note : report.notes()) {
                output.append("- ").append(note).append('\n');
            }
        }
        report.deploymentId().ifPresent(id -> output.append("Central deployment id: ").append(id).append('\n'));
        report.centralOutcome().ifPresent(outcome -> output.append(centralStatusLine(outcome)));
        report.resumeCommand().ifPresent(command -> output.append("Resume with: ").append(command).append('\n'));
        if (report.ok()) {
            output.append(report.uploaded() ? "Uploaded the family.\n" : "No blockers. Nothing uploaded (dry run).\n");
        }
        return output.toString();
    }

    private static String centralStatusLine(PublishCentralPublishOutcome outcome) {
        return switch (outcome) {
            case UPLOADED -> "Central status: uploaded — validation continues on the Portal\n";
            case PUBLISHED -> "Central status: published to Maven Central\n";
            case AWAITING_MANUAL_RELEASE -> "Central status: validated — finish publishing in the Central Portal "
                    + "(https://central.sonatype.com/publishing/deployments)\n";
        };
    }

    private Optional<Path> generateSbom(Path projectRoot) {
        return sbomGenerator.generate(sbom, projectRoot, ZoltCli.version());
    }

    private static String centralProgressResult(PublishCentralPublishOutcome outcome) {
        return switch (outcome) {
            case UPLOADED, PUBLISHED -> "Published to Maven Central";
            case AWAITING_MANUAL_RELEASE -> "Validated on the Central Portal — release it to finish publishing";
        };
    }

    private static String displayPath(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalized).toString()
                : normalized.toString();
    }
}
