package sh.zolt.cli.command.dependency;

import java.nio.file.Path;
import sh.zolt.error.ActionableError;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.service.Workspace;

/**
 * Composes a member edit against the complete effective workspace before it is written.
 *
 * <p>{@code --no-resolve} means "do not refresh artifacts or zolt.lock"; it never means "skip
 * semantic validation". A workspace edit committed without composition could redeclare a root-owned
 * version, platform, or credential ID, introduce a {@code workspace = true} edge no member provides,
 * or collide with another member's effective project identity — all of which the resolving path
 * rejects. This runs the same shadow composition the resolving path runs and stops before resolution.
 */
final class NoResolveWorkspaceComposition {
    private NoResolveWorkspaceComposition() {
    }

    static void requireComposable(Workspace workspace, Path manifestPath, String editedSource) {
        try {
            new ManifestWorkspaceLoader().compose(workspace.root(), manifestPath, editedSource);
        } catch (WorkspaceConfigException | ZoltConfigException | IllegalArgumentException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "The edited manifest does not compose with the workspace: " + exception.getMessage(),
                    "Correct the edit so it composes with the workspace root and its members, then retry. "
                            + "No changes were written.",
                    exception));
        }
    }
}
