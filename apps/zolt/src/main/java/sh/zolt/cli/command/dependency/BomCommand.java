package sh.zolt.cli.command.dependency;

import sh.zolt.cache.ArtifactCacheException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.cli.CommandHumanOutput;
import sh.zolt.cli.command.CommandFailures;
import sh.zolt.cli.command.CommandFrameworkServices;
import sh.zolt.cli.command.CommandProjectDirectory;
import sh.zolt.cli.command.CommandResolveOutput;
import sh.zolt.cli.command.CommandServiceBundles.CommandDependencyEditServices;
import sh.zolt.cli.command.dependency.DependencyEditCommands.BomCommandException;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Owns the two BOM maps a published platform declares.
 *
 * <p>The nesting is semantic disambiguation between {@code [bom.versions]} pins and
 * {@code [bom.imports]} platform imports, not a mechanical reproduction of TOML tables, and only
 * {@code bom versions set} accepts {@code --classifier} and {@code --type} because an imported BOM's
 * artifact semantics are fixed (design §20).
 */
@Command(
        name = "bom",
        description = "Manage published BOM pins and imports.",
        subcommands = {
                BomCommand.VersionsCommand.class,
                BomCommand.ImportsCommand.class
        })
public final class BomCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    @Command(
            name = "versions",
            description = "Manage explicit BOM version pins in [bom.versions].",
            subcommands = {
                    BomCommand.VersionsCommand.SetCommand.class,
                    BomCommand.VersionsCommand.RemoveCommand.class
            })
    public static final class VersionsCommand implements Runnable {
        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }

        @Command(name = "set", description = "Pin a BOM version in zolt.toml and refresh zolt.lock.")
        public static final class SetCommand extends BomEditCommand {
            @Parameters(index = "1", arity = "0..1", paramLabel = "VERSION", description = "Literal version.")
            private String version;

            @Option(names = "--version-ref", description = "Use a version alias declared in [versions].")
            private String versionRef;

            @Option(names = "--classifier", description = "Maven classifier of the pinned artifact.")
            private String classifier;

            @Option(names = "--type", description = "Maven type of the pinned artifact.")
            private String type;

            public SetCommand() {
            }

            SetCommand(ManifestMutationServices manifests, ResolveService resolveService) {
                super(manifests, resolveService);
            }

            @Override
            void execute() {
                DependencyCoordinate pin = coordinate();
                ManifestEditResult edit = edit(current -> AuthoredManifestMutator.setBomVersion(
                        current,
                        pin,
                        new AuthoredBom.Version(
                                selector(current),
                                Optional.ofNullable(classifier),
                                Optional.ofNullable(type))));
                report(
                        edit,
                        "BOM version " + pin,
                        "[bom.versions]",
                        versions(edit.original()).get(pin) == null
                                ? null
                                : DependencyEditCommands.describe(versions(edit.original()).get(pin).selector()),
                        DependencyEditCommands.describe(selector(edit.original())));
            }

            private PlatformSelector selector(AuthoredManifest manifest) {
                return PlatformSelectors.parse(
                        manifest, "BOM version", version, versionRef, BomCommandException::new);
            }

            private static Map<DependencyCoordinate, AuthoredBom.Version> versions(AuthoredManifest manifest) {
                return manifest.packaging().bom().flatMap(AuthoredBom::versions).orElseGet(Map::of);
            }
        }

        @Command(name = "remove", description = "Remove a BOM version pin and refresh zolt.lock.")
        public static final class RemoveCommand extends BomEditCommand {
            public RemoveCommand() {
            }

            RemoveCommand(ManifestMutationServices manifests, ResolveService resolveService) {
                super(manifests, resolveService);
            }

            @Override
            void execute() {
                DependencyCoordinate pin = coordinate();
                removed(
                        edit(current -> AuthoredManifestMutator.removeBomVersion(current, pin)),
                        "BOM version " + pin,
                        "[bom.versions]");
            }
        }
    }

    @Command(
            name = "imports",
            description = "Manage imported BOMs in [bom.imports].",
            subcommands = {
                    BomCommand.ImportsCommand.SetCommand.class,
                    BomCommand.ImportsCommand.RemoveCommand.class
            })
    public static final class ImportsCommand implements Runnable {
        @Spec
        private CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }

        @Command(name = "set", description = "Import a BOM in zolt.toml and refresh zolt.lock.")
        public static final class SetCommand extends BomEditCommand {
            @Parameters(index = "1", arity = "0..1", paramLabel = "VERSION", description = "Literal version.")
            private String version;

            @Option(names = "--version-ref", description = "Use a version alias declared in [versions].")
            private String versionRef;

            public SetCommand() {
            }

            SetCommand(ManifestMutationServices manifests, ResolveService resolveService) {
                super(manifests, resolveService);
            }

            @Override
            void execute() {
                DependencyCoordinate imported = coordinate();
                ManifestEditResult edit = edit(current -> AuthoredManifestMutator.setBomImport(
                        current, imported, selector(current)));
                PlatformSelector existing = imports(edit.original()).get(imported);
                report(
                        edit,
                        "BOM import " + imported,
                        "[bom.imports]",
                        existing == null ? null : DependencyEditCommands.describe(existing),
                        DependencyEditCommands.describe(selector(edit.original())));
            }

            private PlatformSelector selector(AuthoredManifest manifest) {
                return PlatformSelectors.parse(
                        manifest, "BOM import", version, versionRef, BomCommandException::new);
            }

            private static Map<DependencyCoordinate, PlatformSelector> imports(AuthoredManifest manifest) {
                return manifest.packaging().bom().flatMap(AuthoredBom::imports).orElseGet(Map::of);
            }
        }

        @Command(name = "remove", description = "Remove an imported BOM and refresh zolt.lock.")
        public static final class RemoveCommand extends BomEditCommand {
            public RemoveCommand() {
            }

            RemoveCommand(ManifestMutationServices manifests, ResolveService resolveService) {
                super(manifests, resolveService);
            }

            @Override
            void execute() {
                DependencyCoordinate imported = coordinate();
                removed(
                        edit(current -> AuthoredManifestMutator.removeBomImport(current, imported)),
                        "BOM import " + imported,
                        "[bom.imports]");
            }
        }
    }

    /** Shared transaction plumbing for the four BOM map mutations. */
    abstract static class BomEditCommand implements Runnable {
        private final ManifestMutationServices manifests;
        private final ResolveService resolveService;

        @Parameters(index = "0", paramLabel = "GROUP:ARTIFACT", description = "BOM coordinate.")
        private String coordinate;

        @Option(names = "--no-resolve", description = "Update zolt.toml without refreshing zolt.lock.")
        private boolean noResolve;

        @Mixin
        private CommandProjectDirectory projectDirectory = new CommandProjectDirectory();

        @Option(names = "--cache-root", hidden = true)
        private Path cacheRoot = LocalArtifactCache.defaultRoot();

        @Spec
        private CommandSpec spec;

        BomEditCommand() {
            this(
                    CommandFrameworkServices.dependencyEditCommandServices().manifests(),
                    CommandFrameworkServices.dependencyEditCommandServices().resolveService());
        }

        BomEditCommand(ManifestMutationServices manifests, ResolveService resolveService) {
            this.manifests = manifests;
            this.resolveService = resolveService;
        }

        BomEditCommand(CommandDependencyEditServices services) {
            this(services.manifests(), services.resolveService());
        }

        abstract void execute();

        @Override
        public final void run() {
            try {
                execute();
            } catch (BomCommandException
                    | ArtifactCacheException
                    | ResolveException
                    | ZoltConfigException exception) {
                throw CommandFailures.user(spec, exception);
            }
        }

        final DependencyCoordinate coordinate() {
            return DependencyEditCommands.coordinate(coordinate, BomCommandException::new);
        }

        final ManifestEditResult edit(UnaryOperator<AuthoredManifest> mutation) {
            return ManifestEditTransaction.execute(
                    projectDirectory.path(), cacheRoot, noResolve, manifests, resolveService, mutation);
        }

        final void report(
                ManifestEditResult edit,
                String subject,
                String section,
                String existing,
                String requested) {
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            if (existing == null) {
                output.summary("Added " + subject + " with " + requested + " to " + section);
            } else if (existing.equals(requested)) {
                output.detail(subject + " already uses " + requested + " in " + section);
            } else {
                output.summary("Updated " + subject + " from " + existing + " to " + requested
                        + " in " + section);
            }
            resolved(edit, output);
        }

        final void removed(ManifestEditResult edit, String subject, String section) {
            CommandHumanOutput output = CommandHumanOutput.of(spec);
            if (!edit.changed()) {
                output.detail(subject + " is not present in " + section + "; nothing to remove.");
                return;
            }
            output.summary("Removed " + subject + " from " + section);
            resolved(edit, output);
        }

        private void resolved(ManifestEditResult edit, CommandHumanOutput output) {
            if (noResolve) {
                output.detail("Skipped resolve; run zolt resolve to refresh zolt.lock.");
                return;
            }
            if (edit.resolveResult() != null) {
                CommandResolveOutput.print(spec, edit.resolveResult());
            }
        }
    }
}
