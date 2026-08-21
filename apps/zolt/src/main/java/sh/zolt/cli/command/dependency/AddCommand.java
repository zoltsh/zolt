package sh.zolt.cli.command.dependency;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.cli.command.CommandServiceBundles.CommandDependencyEditServices;
import sh.zolt.cli.command.dependency.DependencyEditCommands.AddCommandException;
import sh.zolt.cli.command.dependency.DependencyEditCommands.AddRequest;
import sh.zolt.cli.command.dependency.DependencyEditCommands.DependencyScopeException;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.CoordinateParser;
import sh.zolt.project.VersionPolicy;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "add", description = "Add a dependency to zolt.toml and refresh zolt.lock.")
public final class AddCommand implements Runnable {
    private final CoordinateParser coordinateParser;
    private final ManifestMutationServices manifests;
    private final ResolveService resolveService;

    @Parameters(
            index = "0",
            paramLabel = "GROUP:ARTIFACT[:VERSION]",
            description = "Dependency coordinate.")
    private String argument;

    @Option(
            names = "--scope",
            paramLabel = "<SCOPE>",
            description = "Dependency scope: implementation (default), api, runtime, provided, dev, test, processor, or test-processor.")
    private String scope;

    @Option(names = "--managed", description = "Use a version managed by a declared platform.")
    private boolean managed;

    @Option(names = "--version-ref", description = "Use a version alias declared in [versions].")
    private String versionRef;

    @Option(names = "--no-resolve", description = "Update zolt.toml without refreshing zolt.lock.")
    private boolean noResolve;

    @Mixin
    private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

    @Option(names = "--cache-root", hidden = true)
    private Path cacheRoot = LocalArtifactCache.defaultRoot();

    @Spec
    private CommandSpec spec;

    public AddCommand() {
        this(CommandFrameworkServices.dependencyEditCommandServices());
    }

    private AddCommand(CommandDependencyEditServices services) {
        this(services.coordinateParser(), services.manifests(), services.resolveService());
    }

    AddCommand(
            CoordinateParser coordinateParser,
            ManifestMutationServices manifests,
            ResolveService resolveService) {
        this.coordinateParser = coordinateParser;
        this.manifests = manifests;
        this.resolveService = resolveService;
    }

    @Override
    public void run() {
        try {
            DependencyLane lane = DependencyEditCommands.parseScope(scope, "zolt add");
            Coordinate parsed = parseCoordinate();
            DependencyCoordinate coordinate = new DependencyCoordinate(
                    parsed.groupId() + ":" + parsed.artifactId());
            Path projectRoot = projectDirectory.path();
            ManifestEditResult edit = ManifestEditTransaction.execute(
                    projectRoot,
                    cacheRoot,
                    noResolve,
                    manifests,
                    resolveService,
                    current -> apply(current, request(current, lane, coordinate, parsed)));
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            printSummary(output, edit.original(), request(edit.original(), lane, coordinate, parsed));
            if (noResolve) {
                output.detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
                return;
            }
            if (edit.resolveResult() != null) {
                CommandResolveOutput.print(spec, edit.resolveResult());
            }
        } catch (AddCommandException
                | DependencyScopeException
                | ArtifactCacheException
                | CoordinateParseException
                | ResolveException
                | ZoltConfigException exception) {
            throw CommandFailures.user(spec, exception);
        }
    }

    private Coordinate parseCoordinate() {
        Coordinate parsed = coordinateParser.parse(argument);
        if (managed && versionRef != null) {
            throw new AddCommandException(
                    "`--managed` and `--version-ref` cannot be used together. Choose a platform-managed dependency or a named [versions] alias.");
        }
        if (managed && parsed.version().isPresent()) {
            throw new AddCommandException(
                    "Managed dependency coordinate must not include a version. Use `group:artifact`.");
        }
        if (versionRef != null && versionRef.isBlank()) {
            throw new AddCommandException(
                    "Version alias for --version-ref must be non-empty. Use `--version-ref <alias>`.");
        }
        if (versionRef != null && parsed.version().isPresent()) {
            throw new AddCommandException(
                    "Version-ref dependency coordinate must not include a version. Use `--version-ref "
                            + versionRef + " group:artifact`.");
        }
        return parsed;
    }

    private AddRequest request(
            AuthoredManifest manifest,
            DependencyLane lane,
            DependencyCoordinate coordinate,
            Coordinate parsed) {
        if (managed) {
            return new AddRequest(lane, coordinate, new DependencySelector.Managed());
        }
        if (versionRef != null) {
            DependencyEditCommands.requireAlias(manifest, versionRef, AddCommandException::new);
            return new AddRequest(
                    lane,
                    coordinate,
                    new DependencySelector.VersionReference(
                            DependencyEditCommands.localId(versionRef, AddCommandException::new)));
        }
        String version = parsed.version().orElseThrow(() -> new AddCommandException(
                "Dependency coordinate must include a version. Use `group:artifact:version` or add `--managed` when a declared platform should provide the version."));
        // A SNAPSHOT dependency is written to zolt.toml and left for the resolve-time SnapshotAllowance
        // to accept (workspace member or maven-local overlay) or reject; ranges, dynamic selectors,
        // interpolation, and incomplete literals stay rejected here.
        DependencyEditCommands.validateCommandVersion(
                VersionPolicy.Context.EXTERNAL_DEPENDENCY,
                "dependency",
                version,
                true,
                AddCommandException::new);
        return new AddRequest(lane, coordinate, new DependencySelector.FixedVersion(version));
    }

    /**
     * Writes the requested lane declaration, first removing the same variant from any other ordinary
     * lane so an add moves a dependency instead of leaving two conflicting declarations (design §9.7).
     */
    private static AuthoredManifest apply(AuthoredManifest manifest, AddRequest request) {
        AuthoredDependencyMetadata metadata = DependencyEditCommands
                .find(manifest, request.lane(), request.coordinate())
                .map(AuthoredDependency::metadata)
                .orElseGet(() -> DependencyEditCommands
                        .findMovable(manifest, request.lane(), request.coordinate())
                        .map(AuthoredDependency::metadata)
                        .orElseGet(AuthoredDependencyMetadata::none));
        AuthoredManifest updated = manifest;
        Optional<AuthoredDependency> movable = DependencyEditCommands.findMovable(
                manifest, request.lane(), request.coordinate());
        if (movable.isPresent()) {
            updated = AuthoredManifestMutator.removeDependency(
                    updated, movable.orElseThrow().lane(), request.coordinate());
        }
        return AuthoredManifestMutator.setDependency(
                updated,
                new AuthoredDependency(
                        request.lane(),
                        request.coordinate(),
                        request.selector(),
                        selectorCompatible(request.selector(), metadata)));
    }

    /** Workspace and managed selectors reject artifact metadata, so an incompatible move drops it. */
    private static AuthoredDependencyMetadata selectorCompatible(
            DependencySelector selector, AuthoredDependencyMetadata metadata) {
        if (selector instanceof DependencySelector.Workspace && metadata.hasExternalArtifactMetadata()) {
            return AuthoredDependencyMetadata.none();
        }
        return metadata;
    }

    private void printSummary(CommandHumanOutput output, AuthoredManifest original, AddRequest request) {
        String section = DependencyEditCommands.section(request.lane());
        String requested = DependencyEditCommands.describe(request.selector());
        Optional<AuthoredDependency> existing = DependencyEditCommands.find(
                original, request.lane(), request.coordinate());
        if (existing.isPresent()) {
            String current = DependencyEditCommands.describe(existing.orElseThrow().selector());
            if (current.equals(requested)) {
                output.detail("Dependency " + request.coordinate() + " already uses " + requested
                        + " in [" + section + "]");
            } else {
                output.summary("Updated dependency " + request.coordinate() + " from " + current
                        + " to " + requested + " in [" + section + "]");
            }
            return;
        }
        Optional<AuthoredDependency> moved = DependencyEditCommands.findMovable(
                original, request.lane(), request.coordinate());
        if (moved.isPresent()) {
            output.summary("Moved dependency " + request.coordinate() + " from ["
                    + DependencyEditCommands.section(moved.orElseThrow().lane()) + "] to [" + section
                    + "] with " + requested);
            return;
        }
        output.summary("Added dependency " + request.coordinate() + " with " + requested
                + " to [" + section + "]");
    }
}
