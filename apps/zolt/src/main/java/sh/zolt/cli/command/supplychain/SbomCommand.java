package sh.zolt.cli.command.supplychain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.ZoltCli;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.sbom.CycloneDxSbomWriter;
import sh.zolt.sbom.LicenseIndex;
import sh.zolt.sbom.LockSbomAssembler;
import sh.zolt.sbom.PomLicenseResolver;
import sh.zolt.sbom.SbomModel;
import sh.zolt.sbom.SbomScopeGroup;
import sh.zolt.sbom.SbomScopeSelection;
import sh.zolt.sbom.SbomTimestamp;
import sh.zolt.sbom.SbomWorkspaceMember;
import sh.zolt.sbom.WorkspaceSbomAssembler;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.resolve.WorkspaceMemberGraphRoots;
import sh.zolt.workspace.service.Workspace;

@Command(name = "sbom", description = "Generate a CycloneDX software bill of materials from zolt.lock.")
public final class SbomCommand implements Runnable {
    enum Format {
        CYCLONEDX
    }

    private final ManifestProjectLoader projectLoader;
    private final ZoltLockfileReader lockfileReader;
    private final LockSbomAssembler assembler;
    private final CycloneDxSbomWriter cycloneDxWriter;
    private final Clock clock;
    private final Map<String, String> environment;
    private final String toolVersion;
    private final ManifestWorkspaceLoader workspaceDiscovery = new ManifestWorkspaceLoader();
    private final WorkspaceMemberGraphRoots memberGraphRoots = new WorkspaceMemberGraphRoots();
    private final WorkspaceSbomAssembler workspaceAssembler = new WorkspaceSbomAssembler();

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--workspace", description = "Aggregate the discovered workspace into one BOM.")
    private boolean workspace;

    @Option(names = "--format", paramLabel = "<FORMAT>", description = "Output format: cyclonedx.")
    private Format format = Format.CYCLONEDX;

    @Option(names = "--output", paramLabel = "<PATH>", description = "Write the SBOM to a file instead of stdout.")
    private Path output;

    @Option(names = "--include-provided", description = "Include provided-scope dependencies as optional.")
    private boolean includeProvided;

    @Option(names = "--include-dev", description = "Include dev-scope dependencies as optional.")
    private boolean includeDev;

    @Option(names = "--include-test", description = "Include test-scope dependencies as optional.")
    private boolean includeTest;

    @Option(
            names = "--include-tools",
            description = "Include annotation-processor and tooling dependencies as optional.")
    private boolean includeTools;

    @Option(
            names = "--timestamp",
            paramLabel = "<WHEN>",
            description = "Set metadata.timestamp: an ISO-8601 instant, or `now`. Omitted by default.")
    private String timestamp;

    @Option(
            names = "--offline",
            description = "Accepted for consistency; SBOM generation never uses the network.")
    private boolean offline;

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Spec
    private CommandSpec spec;

    public SbomCommand() {
        this(
                new ManifestProjectLoader(),
                new ZoltLockfileReader(),
                new LockSbomAssembler(),
                new CycloneDxSbomWriter(),
                Clock.systemUTC(),
                System.getenv(),
                ZoltCli.version());
    }

    SbomCommand(
            ManifestProjectLoader projectLoader,
            ZoltLockfileReader lockfileReader,
            LockSbomAssembler assembler,
            CycloneDxSbomWriter cycloneDxWriter,
            Clock clock,
            Map<String, String> environment,
            String toolVersion) {
        this.projectLoader = projectLoader;
        this.lockfileReader = lockfileReader;
        this.assembler = assembler;
        this.cycloneDxWriter = cycloneDxWriter;
        this.clock = clock;
        this.environment = environment;
        this.toolVersion = toolVersion;
    }

    @Override
    public void run() {
        try {
            Optional<String> resolvedTimestamp =
                    SbomTimestamp.resolve(Optional.ofNullable(timestamp), environment, clock);
            SbomScopeSelection selection =
                    new SbomScopeSelection(includeProvided, includeDev, includeTest, includeTools);
            Assembled assembled = workspace
                    ? assembleWorkspace(selection, resolvedTimestamp)
                    : assembleProject(selection, resolvedTimestamp);
            String document = cycloneDxWriter.write(assembled.model());
            if (output != null) {
                writeToFile(document);
            } else {
                CommandOutput.printAndFlush(spec, document);
            }
            warnOnUnknownLicenses(assembled.licenses());
        } catch (LockfileReadException | ZoltConfigException | ActionableException | WorkspaceConfigException exception) {
            throw CommandFailures.user(spec, exception);
        } catch (IOException exception) {
            throw CommandFailures.user(
                    spec,
                    "Could not write the SBOM to " + output + ".",
                    exception);
        }
    }

    private Assembled assembleProject(SbomScopeSelection selection, Optional<String> timestampValue) {
        Path projectRoot = projectDirectory.path();
        ProjectConfig configForMode = projectLoader.load(projectRoot);
        if (configForMode.packageSettings().mode() == sh.zolt.project.PackageMode.BOM) {
            // A BOM has no resolved dependency graph; emit metadata only, never an error.
            CommandHumanOutput.errors(spec).detail(
                    "This project is a BOM; its SBOM contains only BOM metadata (a BOM has no resolved "
                            + "dependency graph, so listing components would be misleading).");
            SbomModel model = assembler.assemble(
                    configForMode,
                    new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(), List.of()),
                    selection,
                    timestampValue,
                    toolVersion,
                    LicenseIndex.empty());
            return new Assembled(model, LicenseIndex.empty());
        }
        Path lockfilePath = projectRoot.resolve("zolt.lock");
        if (!Files.isRegularFile(lockfilePath)) {
            throw new ActionableException(ActionableError.of(
                    "No zolt.lock found at " + lockfilePath + ".",
                    "Run `zolt resolve` to generate it, then re-run `zolt sbom`."));
        }
        ProjectConfig config = projectLoader.load(projectRoot);
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        LicenseIndex licenses = resolveLicenses(lockfile, selection);
        SbomModel model = assembler.assemble(config, lockfile, selection, timestampValue, toolVersion, licenses);
        return new Assembled(model, licenses);
    }

    private Assembled assembleWorkspace(SbomScopeSelection selection, Optional<String> timestampValue) {
        Workspace discovered = workspaceDiscovery.discover(projectDirectory.path())
                .orElseThrow(() -> new ActionableException(ActionableError.of(
                        "No Zolt workspace was found for `zolt sbom --workspace`.",
                        "Run from a workspace root, or drop --workspace to build a single-project SBOM.")));
        Path lockfilePath = discovered.root().resolve("zolt.lock");
        if (!Files.isRegularFile(lockfilePath)) {
            throw new ActionableException(ActionableError.of(
                    "No zolt.lock found at " + lockfilePath + ".",
                    "Run `zolt resolve --workspace` to generate it, then re-run `zolt sbom --workspace`."));
        }
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        sh.zolt.lockfile.WorkspaceGraphLockCapability.requireMemberGraphEvidence(lockfile);
        LicenseIndex licenses = resolveLicenses(lockfile, selection);
        List<SbomWorkspaceMember> members = discovered.members().stream()
                .map(member -> new SbomWorkspaceMember(
                        member.path(),
                        member.config(),
                        memberGraphRoots.roots(member.path(), member.config(), lockfile, discovered)))
                .toList();
        SbomModel model = workspaceAssembler.assemble(
                discovered.config().name(), members, lockfile, selection, timestampValue, toolVersion, licenses);
        return new Assembled(model, licenses);
    }

    private record Assembled(SbomModel model, LicenseIndex licenses) {
    }

    private LicenseIndex resolveLicenses(ZoltLockfile lockfile, SbomScopeSelection selection) {
        List<LockPackage> externalInScope = lockfile.packages().stream()
                .filter(lockPackage -> selection.includes(SbomScopeGroup.of(lockPackage.scope())))
                .filter(lockPackage -> lockPackage.pom().isPresent())
                .toList();
        return new PomLicenseResolver(cacheRoot).index(externalInScope);
    }

    private void warnOnUnknownLicenses(LicenseIndex licenses) {
        if (licenses.unresolved().isEmpty()) {
            return;
        }
        CommandHumanOutput.errors(spec).detail(licenses.unresolved().size()
                + " dependencies have unknown licenses; run `zolt resolve` to cache their POMs for complete data.");
    }

    private void writeToFile(String document) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        Files.writeString(normalized, document, StandardCharsets.UTF_8);
    }
}
