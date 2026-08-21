package sh.zolt.cli.command.dependency;

import sh.zolt.cli.command.CommandServiceClusters.CommandConfigEditServices;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;

/**
 * The manifest read/write pair the source-safe mutation commands construct.
 *
 * <p>Every other command reads through the final loaders. Keeping the legacy parser and writer
 * behind this one factory inside the mutation package makes their remaining reach explicit and gives
 * the cleanup phase a single call site to retire.
 */
public final class ManifestMutationServices {
    private ManifestMutationServices() {
    }

    /** The read/write/resolve trio shared by the dependency and version-alias mutation commands. */
    public static CommandConfigEditServices configEditServices(ResolveService resolveService) {
        return new CommandConfigEditServices(
                new ZoltTomlParser(), new ZoltTomlWriter(), resolveService);
    }
}
