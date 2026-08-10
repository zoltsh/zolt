package sh.zolt.cli.command.dependency;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.net.CommandNetwork;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.maven.metadata.MetadataCache;
import sh.zolt.maven.metadata.RepositoryMetadataService;
import sh.zolt.maven.repository.RepositoryAccessException;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.update.ExactUpdateOptions;
import sh.zolt.update.UpdateCeiling;
import sh.zolt.update.UpdateEngine;
import sh.zolt.update.UpdateOptions;
import sh.zolt.update.UpdateTargetId;
import sh.zolt.workspace.WorkspaceConfigException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
        name = "update",
        description = "Update dependency, platform, and version-alias versions in zolt.toml.")
public final class UpdateCommand implements Runnable {
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

    @Option(names = "--target-id", description = "Update one schema-v2 target ID.")
    private String targetId;

    @Option(names = "--to", description = "Exact destination version for --target-id.")
    private String toVersion;

    @Option(names = "--dry-run", description = "Print the planned edits without writing zolt.toml.")
    private boolean dryRun;

    @Option(names = "--no-resolve", description = "Update zolt.toml without refreshing zolt.lock.")
    private boolean noResolve;

    @Option(names = "--include-prereleases", description = "Allow prerelease versions as update targets.")
    private boolean includePrereleases;

    @Option(names = "--offline", description = "Use only cached version listings; do not fetch metadata.")
    private boolean offline;

    @Option(names = "--latest", description = "Allow updates across major versions.")
    private boolean latest;

    @Option(names = "--patch", description = "Cap updates at patch changes.")
    private boolean patch;

    @Option(names = "--minor", description = "Cap updates at minor changes within the current major.")
    private boolean minor;

    @Option(names = "--major", description = "Allow updates up to a new major version.")
    private boolean major;

    @Parameters(
            arity = "0..*",
            paramLabel = "<SELECTOR>",
            description = "Restrict the update to a coordinate, version alias, or section token.")
    private List<String> selectors = new ArrayList<>();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Spec
    private CommandSpec spec;

    private final PolicyUpdateRunner policyRunner;
    private final ExactUpdateRunner exactRunner;

    public UpdateCommand() {
        this(
                CommandFrameworkServices.versionAliasCommandServices().tomlParser(),
                CommandFrameworkServices.versionAliasCommandServices().tomlWriter(),
                CommandFrameworkServices.versionAliasCommandServices().resolveService(),
                defaultEngine());
    }

    UpdateCommand(
            ZoltTomlParser tomlParser,
            ZoltTomlWriter tomlWriter,
            ResolveService resolveService,
            UpdateEngine engine) {
        this(tomlParser, tomlWriter, resolveService, engine, () -> {});
    }

    UpdateCommand(
            ZoltTomlParser tomlParser,
            ZoltTomlWriter tomlWriter,
            ResolveService resolveService,
            UpdateEngine engine,
            Runnable exactBeforeExecution) {
        this.policyRunner = new PolicyUpdateRunner(tomlParser, tomlWriter, resolveService, engine);
        this.exactRunner = new ExactUpdateRunner(
                tomlParser,
                tomlWriter,
                resolveService,
                new DependencyUpdateScopeResolver(),
                exactBeforeExecution);
    }

    @Override
    public void run() {
        try {
            boolean exact = validateMode();
            Path start = projectDirectory.path();
            if (exact) {
                exactRunner.run(
                        spec,
                        start,
                        cacheRoot,
                        parseTargetId(),
                        new ExactUpdateOptions(toVersion, includePrereleases),
                        dryRun,
                        noResolve,
                        format == Format.JSON);
            } else {
                policyRunner.run(
                        spec,
                        start,
                        cacheRoot,
                        new UpdateOptions(ceiling(), includePrereleases, offline, selectors),
                        dryRun,
                        noResolve,
                        format == Format.JSON);
            }
        } catch (ActionableException | ArtifactCacheException | LockfileReadException
                | RepositoryAccessException | ResolveException | WorkspaceConfigException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private boolean validateMode() {
        boolean exact = targetId != null || toVersion != null;
        boolean schemaSelected = spec.commandLine().getParseResult().hasMatchedOption("--schema-version");
        if (schemaSelected && format != Format.JSON) {
            throw invalid(
                    "`--schema-version` is available only with `--format json`.",
                    "Remove `--schema-version`, or select JSON output.");
        }
        if (!schemaVersion.equals("1") && !schemaVersion.equals("2")) {
            throw invalid("Unsupported update JSON schema version `" + schemaVersion + "`.", "Use schema 1 or 2.");
        }
        if (!exact && schemaVersion.equals("2")) {
            throw invalid(
                    "Update JSON schema v2 is available only for exact target updates.",
                    "Pass both `--target-id` and `--to`, or use schema 1 for policy-driven update.");
        }
        if (!exact) {
            return false;
        }
        if (targetId == null || toVersion == null) {
            throw invalid(
                    "`--target-id` and `--to` are required together.",
                    "Pass both options to select one exact update target.");
        }
        if (!selectors.isEmpty()) {
            throw invalid(
                    "Exact target update cannot be combined with positional selectors.",
                    "Remove the selectors; the target ID already identifies exactly one declaration.");
        }
        if (patch || minor || major || latest) {
            throw invalid(
                    "Exact target update cannot be combined with update ceiling flags.",
                    "Remove `--patch`, `--minor`, `--major`, and `--latest`; `--to` is authoritative.");
        }
        if (offline) {
            throw invalid(
                    "Exact target update cannot be combined with `--offline`.",
                    "Remove `--offline`; exact mode performs no metadata discovery.");
        }
        if (format == Format.JSON && !schemaVersion.equals("2")) {
            throw invalid(
                    "Exact target JSON output requires schema v2.",
                    "Pass `--schema-version 2` with `--format json`.");
        }
        return true;
    }

    private UpdateTargetId parseTargetId() {
        try {
            return UpdateTargetId.parse(targetId);
        } catch (IllegalArgumentException exception) {
            throw invalid(
                    "Invalid Zolt update target ID.",
                    "Use a targetId from `zolt outdated --format json --schema-version 2`.");
        }
    }

    private UpdateCeiling ceiling() {
        if (latest) {
            return UpdateCeiling.LATEST;
        }
        if (major) {
            return UpdateCeiling.MAJOR;
        }
        if (minor) {
            return UpdateCeiling.MINOR;
        }
        if (patch) {
            return UpdateCeiling.PATCH;
        }
        return UpdateCeiling.DEFAULT;
    }

    private static ActionableException invalid(String summary, String remediation) {
        return new ActionableException(summary, remediation);
    }

    private static UpdateEngine defaultEngine() {
        RepositoryMetadataService discovery = new RepositoryMetadataService(
                CommandNetwork.repositoryClient(), new MetadataCache(LocalArtifactCache.defaultRoot()));
        return new UpdateEngine(discovery);
    }
}
