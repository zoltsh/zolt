package sh.zolt.workspace.discovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Non-authoritative root lookup used only to choose the workspace mutation lock. */
final class WorkspaceRootLocator {
    private WorkspaceRootLocator() {
    }

    static Optional<Path> find(
            Path startDirectory,
            WorkspaceDiscoveryService discovery) {
        Path current = startDirectory.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (discovery.workspaceConfigPath(current).isPresent()) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }
}
