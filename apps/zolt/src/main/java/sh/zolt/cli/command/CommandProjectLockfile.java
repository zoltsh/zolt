package sh.zolt.cli.command;

import sh.zolt.workspace.discovery.ManifestProject;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.service.Workspace;
import java.nio.file.Path;

/**
 * The one lockfile that governs a project directory.
 *
 * <p>Design §4.5: a workspace has exactly one authoritative lockfile, at its root, and no command
 * creates or consumes a member-local {@code zolt.lock}. A command started inside a member directory
 * therefore projects its answer from the root lock — the member view of the graph is a selection out
 * of the one workspace-wide resolution, not a resolution of its own. A directory outside every
 * workspace keeps its own lockfile.
 */
public final class CommandProjectLockfile {
    private static final String LOCKFILE = "zolt.lock";

    private CommandProjectLockfile() {
    }

    /** The authoritative lockfile path for an already-discovered project. */
    public static Path path(ManifestProject project) {
        return project.workspaceRoot().orElseGet(project::directory).resolve(LOCKFILE);
    }

    /**
     * The authoritative lockfile path for a directory, for the commands that read lock facts without
     * needing the project's configuration. Only a directory the workspace actually expanded into — its
     * root or one of its members — is governed by the root lock; anything else keeps its own.
     */
    public static Path path(Path projectDirectory, ManifestWorkspaceLoader workspaceLoader) {
        Path directory = projectDirectory.toAbsolutePath().normalize();
        return workspaceLoader.discover(directory)
                .filter(workspace -> owns(workspace, directory))
                .map(Workspace::root)
                .orElse(directory)
                .resolve(LOCKFILE);
    }

    private static boolean owns(Workspace workspace, Path directory) {
        if (workspace.root().toAbsolutePath().normalize().equals(directory)) {
            return true;
        }
        return workspace.members().stream()
                .anyMatch(member -> member.directory().toAbsolutePath().normalize().equals(directory));
    }

    /**
     * The lock's member identity for {@code project}: {@code .} for a standalone project, otherwise its
     * workspace-relative directory, which discovery guarantees equals the member's authored path.
     */
    public static String memberPath(ManifestProject project) {
        return project.workspaceRoot()
                .map(root -> root.relativize(project.directory()).toString().replace('\\', '/'))
                .filter(path -> !path.isEmpty())
                .orElse(".");
    }

    /** The command that regenerates {@code project}'s lockfile, named for the scope that owns it. */
    public static String resolveCommand(ManifestProject project) {
        return project.workspaceRoot().isPresent() ? "zolt resolve --workspace" : "zolt resolve";
    }
}
