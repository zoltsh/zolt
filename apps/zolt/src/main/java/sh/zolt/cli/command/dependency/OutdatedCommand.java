package sh.zolt.cli.command.dependency;

import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandOutput;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.net.CommandNetwork;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.maven.metadata.MetadataCache;
import sh.zolt.maven.metadata.RepositoryMetadataService;
import sh.zolt.maven.repository.RepositoryAccessException;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.update.OutdatedEngine;
import sh.zolt.update.OutdatedJsonRenderer;
import sh.zolt.update.OutdatedJsonRendererV2;
import sh.zolt.update.OutdatedOptions;
import sh.zolt.update.OutdatedReport;
import sh.zolt.update.OutdatedScope;
import sh.zolt.update.OutdatedTextRenderer;
import sh.zolt.workspace.WorkspaceConfigException;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "outdated",
        description = "Report available dependency, platform, and tooling version updates.")
public final class OutdatedCommand implements Runnable {
    enum Format {
        TEXT,
        JSON
    }

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--format", description = "Output format: text or json.")
    private Format format = Format.TEXT;

    @Option(names = "--schema-version", description = "JSON schema version: 1 or 2.")
    private String schemaVersion = "1";

    @Option(names = "--include-prereleases", description = "Include prerelease versions as update candidates.")
    private boolean includePrereleases;

    @Option(names = "--all", description = "Include surfaces that are already up to date.")
    private boolean all;

    @Option(names = "--offline", description = "Use only cached version listings; do not fetch metadata.")
    private boolean offline;

    @Parameters(
            arity = "0..*",
            paramLabel = "<SELECTOR>",
            description = "Restrict the report to a coordinate, version alias, or section token.")
    private List<String> selectors = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    private final OutdatedEngine engine;
    private final DependencyUpdateScopeResolver scopeResolver;

    public OutdatedCommand() {
        this(defaultEngine(), new DependencyUpdateScopeResolver());
    }

    OutdatedCommand(OutdatedEngine engine, DependencyUpdateScopeResolver scopeResolver) {
        this.engine = engine;
        this.scopeResolver = scopeResolver;
    }

    @Override
    public void run() {
        try {
            int selectedSchema = selectedSchema();
            List<OutdatedScope> reportScopes = scopeResolver.reportScopes(projectDirectory.path(), selectedSchema);
            OutdatedOptions options = new OutdatedOptions(includePrereleases, all, offline, selectors);
            OutdatedReport report = engine.report(reportScopes, options);
            String output = format == Format.JSON
                    ? renderJson(report, selectedSchema)
                    : new OutdatedTextRenderer().render(report);
            CommandOutput.printAndFlush(spec, output);
        } catch (LockfileReadException | ZoltConfigException | RepositoryAccessException
                | WorkspaceConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private int selectedSchema() {
        boolean explicitlySelected = spec.commandLine().getParseResult().hasMatchedOption("--schema-version");
        if (explicitlySelected && format != Format.JSON) {
            throw new ZoltConfigException("--schema-version is available only with --format json.");
        }
        if (!schemaVersion.equals("1") && !schemaVersion.equals("2")) {
            throw new ZoltConfigException("Unsupported outdated JSON schema version `"
                    + schemaVersion
                    + "`. Use 1 or 2.");
        }
        return Integer.parseInt(schemaVersion);
    }

    private static OutdatedEngine defaultEngine() {
        // Route metadata discovery through the composition root so the corporate proxy and CA bundle
        // from the user-global [network] config are honored, matching zolt resolve.
        RepositoryMetadataService discovery = new RepositoryMetadataService(
                CommandNetwork.repositoryClient(), new MetadataCache(LocalArtifactCache.defaultRoot()));
        return new OutdatedEngine(discovery);
    }

    private static String renderJson(OutdatedReport report, int schemaVersion) {
        return schemaVersion == 2
                ? new OutdatedJsonRendererV2().render(report)
                : new OutdatedJsonRenderer().render(report);
    }
}
