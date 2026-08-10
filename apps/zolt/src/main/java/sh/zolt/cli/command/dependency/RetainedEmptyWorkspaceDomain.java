package sh.zolt.cli.command.dependency;

import sh.zolt.workspace.toml.WorkspaceConfigParser;
import java.nio.file.Files;
import java.nio.file.Path;

/** Recognizes only the valid empty workspace section retained inside a standalone project. */
final class RetainedEmptyWorkspaceDomain {
    private RetainedEmptyWorkspaceDomain() {
    }

    static boolean existsAt(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        if (Files.isRegularFile(root.resolve(WorkspaceConfigParser.WORKSPACE_FILE))) {
            return false;
        }
        return new WorkspaceConfigParser()
                .isRetainedEmptyRootWorkspace(root.resolve(WorkspaceConfigParser.ROOT_CONFIG_FILE));
    }
}
