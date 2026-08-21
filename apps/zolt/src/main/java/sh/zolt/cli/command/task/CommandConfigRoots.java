package sh.zolt.cli.command.task;

import sh.zolt.command.CommandConfig;
import sh.zolt.command.ManifestCommandConfigAdapter;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.ManifestWorkspaceDiscovery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the manifest that owns {@code [tasks.&lt;id&gt;]} and {@code [aliases]} for one directory.
 *
 * <p>A workspace root owns its workspace's commands (design §4.5), so discovery stops at the nearest
 * ancestor whose manifest declares {@code [workspace]}. Outside a workspace the nearest
 * {@code zolt.toml} owns them. The pre-cut second manifest name is gone (design §21), so no
 * companion file participates in discovery.
 */
final class CommandConfigRoots {
    private static final String MANIFEST = "zolt.toml";

    private final ManifestWorkspaceDiscovery discovery;
    private final ManifestProjectConfigLoader manifestLoader;

    CommandConfigRoots() {
        this(new ManifestWorkspaceDiscovery(), new ManifestProjectConfigLoader());
    }

    CommandConfigRoots(
            ManifestWorkspaceDiscovery discovery,
            ManifestProjectConfigLoader manifestLoader) {
        this.discovery = discovery;
        this.manifestLoader = manifestLoader;
    }

    Path discoverConfig(Path startDirectory) {
        Optional<Path> workspaceRoot = workspaceRoot(startDirectory);
        if (workspaceRoot.isPresent()) {
            return workspaceRoot.orElseThrow().resolve(MANIFEST);
        }
        Path current = startDirectory.toAbsolutePath().normalize();
        if (Files.isRegularFile(current)) {
            current = current.getParent();
        }
        while (current != null) {
            Path manifest = current.resolve(MANIFEST).normalize();
            if (Files.isRegularFile(manifest)) {
                return manifest;
            }
            current = current.getParent();
        }
        throw new ZoltConfigException(
                "Could not find zolt.toml command config. Run from a project or workspace directory, "
                        + "or add zolt.toml with [tasks.<id>].");
    }

    /** The tasks and aliases authored in one manifest. */
    CommandConfig commands(Path manifestPath) {
        return ManifestCommandConfigAdapter.authored(
                manifestLoader.document(manifestPath).authored().commands());
    }

    private Optional<Path> workspaceRoot(Path startDirectory) {
        try {
            return discovery.discoverRoot(startDirectory);
        } catch (WorkspaceConfigException exception) {
            throw new ZoltConfigException(exception.getMessage());
        }
    }
}
