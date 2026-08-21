package sh.zolt.cli.command.supplychain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import sh.zolt.cache.LocalArtifactCache;
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
import sh.zolt.sbom.LicenseIndex;
import sh.zolt.sbom.LicenseNoticesWriter;
import sh.zolt.sbom.LicensePolicyAnnotations;
import sh.zolt.sbom.LicensePolicyScope;
import sh.zolt.sbom.LicenseReport;
import sh.zolt.sbom.LicenseReportBuilder;
import sh.zolt.sbom.LicenseReportJsonWriter;
import sh.zolt.sbom.LicenseReportTextWriter;
import sh.zolt.sbom.LockSbomAssembler;
import sh.zolt.sbom.PomLicenseResolver;
import sh.zolt.sbom.SbomComponent;
import sh.zolt.sbom.SbomModel;
import sh.zolt.sbom.SbomScopeGroup;
import sh.zolt.sbom.SbomScopeSelection;
import sh.zolt.sbom.SbomWorkspaceMember;
import sh.zolt.sbom.WorkspaceSbomAssembler;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.resolve.WorkspaceMemberGraphRoots;
import sh.zolt.workspace.service.Workspace;

@Command(name = "licenses", description = "Report the licenses of resolved dependencies from cached POMs.")
public final class LicensesCommand implements Runnable {
    /**
     * The scopes {@code zolt check --check license-policy} evaluates, and therefore the only scopes an
     * annotation may claim. {@code LicensePolicyQualityCheck} selects the same way; both call this one
     * factory so the report and the command it names can never drift apart.
     */
    private static final SbomScopeSelection ENFORCED_SCOPES = SbomScopeSelection.requiredOnly();

    enum Format {
        TEXT,
        JSON
    }

    private final ZoltTomlParser tomlParser;
    private final ZoltLockfileReader lockfileReader;
    private final LockSbomAssembler assembler;
    private final String toolVersion;
    private final LicenseReportBuilder reportBuilder = new LicenseReportBuilder();
    private final LicenseReportTextWriter textWriter = new LicenseReportTextWriter();
    private final LicenseReportJsonWriter jsonWriter = new LicenseReportJsonWriter();
    private final LicenseNoticesWriter noticesWriter = new LicenseNoticesWriter();
    private final ManifestWorkspaceLoader workspaceDiscovery = new ManifestWorkspaceLoader();
    private final WorkspaceMemberGraphRoots memberGraphRoots = new WorkspaceMemberGraphRoots();
    private final WorkspaceSbomAssembler workspaceAssembler = new WorkspaceSbomAssembler();
    private final WorkspaceLicensePolicyScopes workspaceScopes = new WorkspaceLicensePolicyScopes();

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--workspace", description = "Report third-party licenses across the discovered workspace.")
    private boolean workspace;

    @Option(names = "--format", paramLabel = "<FORMAT>", description = "Output format: text or json.")
    private Format format = Format.TEXT;

    @Option(
            names = "--notices",
            paramLabel = "<PATH>",
            description = "Also write a deterministic THIRD_PARTY notices file to the given path.")
    private Path notices;

    @Option(names = "--include-provided", description = "Include provided-scope dependencies.")
    private boolean includeProvided;

    @Option(names = "--include-dev", description = "Include dev-scope dependencies.")
    private boolean includeDev;

    @Option(names = "--include-test", description = "Include test-scope dependencies.")
    private boolean includeTest;

    @Option(names = "--include-tools", description = "Include annotation-processor and tooling dependencies.")
    private boolean includeTools;

    @Option(
            names = "--offline",
            description = "Accepted for consistency; license resolution never uses the network.")
    private boolean offline;

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Spec
    private CommandSpec spec;

    public LicensesCommand() {
        this(new ZoltTomlParser(), new ZoltLockfileReader(), new LockSbomAssembler(), ZoltCli.version());
    }

    LicensesCommand(
            ZoltTomlParser tomlParser,
            ZoltLockfileReader lockfileReader,
            LockSbomAssembler assembler,
            String toolVersion) {
        this.tomlParser = tomlParser;
        this.lockfileReader = lockfileReader;
        this.assembler = assembler;
        this.toolVersion = toolVersion;
    }

    @Override
    public void run() {
        try {
            SbomScopeSelection selection =
                    new SbomScopeSelection(includeProvided, includeDev, includeTest, includeTools);
            Resolved resolved = workspace ? resolveWorkspace(selection) : resolveProject(selection);
            LicenseReport report = reportBuilder.build(resolved.components(), resolved.index());
            LicensePolicyAnnotations annotations = LicensePolicyAnnotations.evaluate(
                    resolved.enforced(), resolved.index(), resolved.scopes());

            String document = format == Format.JSON
                    ? jsonWriter.write(report, annotations)
                    : textWriter.write(report, annotations);
            CommandOutput.printAndFlush(spec, document);
            if (notices != null) {
                writeNotices(resolved.components(), resolved.index());
            }
        } catch (LockfileReadException | ZoltConfigException | ActionableException | WorkspaceConfigException exception) {
            throw CommandFailures.user(spec, exception);
        } catch (IOException exception) {
            throw CommandFailures.user(spec, "Could not write the notices file to " + notices + ".", exception);
        }
    }

    private Resolved resolveProject(SbomScopeSelection selection) {
        Path projectRoot = projectDirectory.path();
        Path lockfilePath = projectRoot.resolve("zolt.lock");
        if (!Files.isRegularFile(lockfilePath)) {
            throw new ActionableException(ActionableError.of(
                    "No zolt.lock found at " + lockfilePath + ".",
                    "Run `zolt resolve` to generate it, then re-run `zolt licenses`."));
        }
        ProjectConfig config = tomlParser.parse(projectRoot.resolve("zolt.toml"));
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        LicenseIndex index = resolveLicenses(lockfile, selection);
        SbomModel model = assembler.assemble(config, lockfile, selection, Optional.empty(), toolVersion, index);
        SbomModel enforcedModel =
                assembler.assemble(config, lockfile, ENFORCED_SCOPES, Optional.empty(), toolVersion, index);
        List<SbomComponent> enforced = externalComponents(enforcedModel, index);
        return new Resolved(
                externalComponents(model, index),
                enforced,
                index,
                List.of(new LicensePolicyScope(config, enforced)));
    }

    private Resolved resolveWorkspace(SbomScopeSelection selection) {
        Workspace discovered = workspaceDiscovery.discover(projectDirectory.path())
                .orElseThrow(() -> new ActionableException(ActionableError.of(
                        "No Zolt workspace was found for `zolt licenses --workspace`.",
                        "Run from a workspace root, or drop --workspace for a single-project report.")));
        Path lockfilePath = discovered.root().resolve("zolt.lock");
        if (!Files.isRegularFile(lockfilePath)) {
            throw new ActionableException(ActionableError.of(
                    "No zolt.lock found at " + lockfilePath + ".",
                    "Run `zolt resolve --workspace` to generate it, then re-run `zolt licenses --workspace`."));
        }
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        LicenseIndex index = resolveLicenses(lockfile, selection);
        List<SbomWorkspaceMember> members = discovered.members().stream()
                .map(member -> new SbomWorkspaceMember(
                        member.path(),
                        member.config(),
                        memberGraphRoots.roots(member.path(), member.config(), lockfile, discovered)))
                .toList();
        SbomModel model = workspaceAssembler.assemble(
                discovered.config().name(), members, lockfile, selection, Optional.empty(), toolVersion, index);
        SbomModel enforcedModel = workspaceAssembler.assemble(
                discovered.config().name(), members, lockfile, ENFORCED_SCOPES, Optional.empty(), toolVersion, index);
        List<SbomComponent> enforced = externalComponents(enforcedModel, index);
        return new Resolved(
                externalComponents(model, index),
                enforced,
                index,
                workspaceScopes.from(discovered, lockfile, enforced));
    }

    /** The report covers resolvable third-party dependencies; first-party members are excluded. */
    private static List<SbomComponent> externalComponents(SbomModel model, LicenseIndex index) {
        return model.components().stream()
                .filter(component -> index.byCoordinate().containsKey(coordinate(component)))
                .toList();
    }

    private static String coordinate(SbomComponent component) {
        return component.group() + ":" + component.name() + ":" + component.version();
    }

    /**
     * {@code components} is what the report lists — every scope the user selected. {@code enforced} is
     * the separate {@link #ENFORCED_SCOPES} assembly the annotations are computed from, so an
     * optional-scope entry stays listed but unannotated. {@code scopes} pairs each member-local
     * {@code [dependencyPolicy.licenses]} with the enforced closure it governs.
     */
    private record Resolved(
            List<SbomComponent> components,
            List<SbomComponent> enforced,
            LicenseIndex index,
            List<LicensePolicyScope> scopes) {
    }

    private LicenseIndex resolveLicenses(ZoltLockfile lockfile, SbomScopeSelection selection) {
        List<LockPackage> externalInScope = lockfile.packages().stream()
                .filter(lockPackage -> selection.includes(SbomScopeGroup.of(lockPackage.scope())))
                .filter(lockPackage -> lockPackage.pom().isPresent())
                .toList();
        return new PomLicenseResolver(cacheRoot).index(externalInScope);
    }

    private void writeNotices(List<SbomComponent> components, LicenseIndex index) throws IOException {
        Path normalized = notices.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        Files.writeString(normalized, noticesWriter.write(components, index), StandardCharsets.UTF_8);
    }
}
