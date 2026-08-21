package sh.zolt.workspace.discovery;

import java.nio.file.Path;
import sh.zolt.workspace.service.Workspace;

/**
 * The single place the final-language workspace equivalence test touches the legacy dialect.
 *
 * <p>When {@link WorkspaceDiscoveryService} is deleted in the cleanup phase, delete this helper and
 * re-point {@link ManifestWorkspaceLoaderEquivalenceTest} at explicit expected values; every other
 * line of that test already describes the final language only.
 */
final class LegacyWorkspaceDialect {
    private static final WorkspaceDiscoveryService SERVICE = new WorkspaceDiscoveryService();

    private LegacyWorkspaceDialect() {
    }

    /** Loads the legacy-dialect workspace rooted at {@code root}. */
    static Workspace load(Path root) {
        return SERVICE.load(root);
    }
}
