package sh.zolt.cli.command.task;

import sh.zolt.command.CommandConfig;
import sh.zolt.command.ManifestCommandConfigAdapter;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.DiscoveredWorkspace;
import sh.zolt.workspace.discovery.ManifestWorkspaceDiscovery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the effective {@code [tasks.&lt;id&gt;]} and {@code [aliases]} namespace for one directory.
 *
 * <p>A command started inside a workspace member evaluates that member with the workspace root's
 * shared configuration even without {@code --workspace} (design §4.5), so root tasks and aliases stay
 * visible from a member and member-local ones join the same namespace. Root and member IDs share one
 * namespace and collide rather than shadow, which workspace composition already enforces. Outside a
 * workspace the nearest {@code zolt.toml} owns the namespace by itself. The pre-cut second manifest
 * name is gone (design §21), so no companion file participates in discovery.
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

    /**
     * The commands visible from {@code startDirectory}, together with the root their {@code cwd}
     * values resolve against. Inside a workspace that root is the workspace root: a member task
     * carries its own owning directory, so one base plus per-task provenance covers both cases.
     */
    LoadedCommandConfig load(Path startDirectory) {
        Path start = normalize(startDirectory);
        Optional<DiscoveredWorkspace> workspace = discoverWorkspace(start);
        if (workspace.isPresent()) {
            DiscoveredWorkspace discovered = workspace.orElseThrow();
            Path manifest = discovered.root().resolve(MANIFEST);
            return member(discovered, start)
                    .map(path -> new LoadedCommandConfig(manifest, effective(discovered, path)))
                    .orElseGet(() -> new LoadedCommandConfig(manifest, authored(manifest)));
        }
        Path manifest = nearestManifest(start);
        return new LoadedCommandConfig(manifest, authored(manifest));
    }

    private CommandConfig authored(Path manifestPath) {
        return ManifestCommandConfigAdapter.authored(
                manifestLoader.document(manifestPath).authored().commands());
    }

    private CommandConfig effective(DiscoveredWorkspace workspace, WorkspaceMemberPath member) {
        EffectiveManifest effective = workspace.effective().members().get(member);
        if (effective == null) {
            return authored(workspace.root().resolve(MANIFEST));
        }
        return ManifestCommandConfigAdapter.effective(effective.project().shared().commands());
    }

    /** The deepest member whose directory contains the start directory, when there is one. */
    private static Optional<WorkspaceMemberPath> member(DiscoveredWorkspace workspace, Path start) {
        return workspace.members().entrySet().stream()
                .filter(entry -> start.startsWith(entry.getValue().directory()))
                .max(Comparator.comparingInt(entry -> entry.getValue().directory().getNameCount()))
                .map(Map.Entry::getKey);
    }

    private Optional<DiscoveredWorkspace> discoverWorkspace(Path startDirectory) {
        try {
            return discovery.discover(startDirectory);
        } catch (WorkspaceConfigException | IllegalArgumentException exception) {
            // A root/member command ID collision fails workspace composition (design §4.5); report it
            // as the configuration error it is rather than an internal failure.
            throw new ZoltConfigException(exception.getMessage());
        }
    }

    private static Path nearestManifest(Path startDirectory) {
        Path current = startDirectory;
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

    private static Path normalize(Path startDirectory) {
        Path current = startDirectory.toAbsolutePath().normalize();
        return Files.isRegularFile(current) ? current.getParent() : current;
    }
}
