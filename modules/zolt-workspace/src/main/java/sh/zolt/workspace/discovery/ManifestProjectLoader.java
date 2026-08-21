package sh.zolt.workspace.discovery;

import java.nio.file.Path;
import java.util.Optional;
import sh.zolt.command.CommandConfig;
import sh.zolt.command.ManifestCommandConfigAdapter;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.project.CoverageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;

/**
 * Loads the single project a command was started in.
 *
 * <p>Design §4.5 "Command discovery": a directory that a workspace expanded into a member is
 * evaluated with the workspace root's shared configuration whether or not {@code --workspace} was
 * supplied. Only a directory outside every workspace composes standalone, so this loader — not
 * {@link ManifestProjectConfigLoader} — is the entry point for every command that reads "the project
 * here".
 *
 * <p>Membership, composition, and legacy projection stay owned by {@link ManifestWorkspaceDiscovery}
 * and {@link ManifestWorkspaceLoader}; this loader only chooses between the member view and the
 * standalone one.
 */
public final class ManifestProjectLoader {
    private static final String MANIFEST = "zolt.toml";

    private final ManifestWorkspaceDiscovery discovery;
    private final ManifestWorkspaceLoader workspaceLoader;
    private final ManifestProjectConfigLoader standaloneLoader;
    private final EffectiveProjectConfigAdapter adapter;

    public ManifestProjectLoader() {
        this(
                new ManifestWorkspaceDiscovery(),
                new ManifestWorkspaceLoader(),
                new ManifestProjectConfigLoader(),
                new EffectiveProjectConfigAdapter());
    }

    ManifestProjectLoader(
            ManifestWorkspaceDiscovery discovery,
            ManifestWorkspaceLoader workspaceLoader,
            ManifestProjectConfigLoader standaloneLoader,
            EffectiveProjectConfigAdapter adapter) {
        this.discovery = discovery;
        this.workspaceLoader = workspaceLoader;
        this.standaloneLoader = standaloneLoader;
        this.adapter = adapter;
    }

    /** The legacy project config the build engine consumes. */
    public ProjectConfig load(Path projectDirectory) {
        return project(projectDirectory).config();
    }

    /** The complete final view of the project rooted at {@code projectDirectory}. */
    public ManifestProject project(Path projectDirectory) {
        Path directory = projectDirectory.toAbsolutePath().normalize();
        Optional<DiscoveredWorkspace> discovered = discovery.discover(directory);
        if (discovered.isPresent()) {
            Optional<ManifestProject> member = member(discovered.orElseThrow(), directory);
            if (member.isPresent()) {
                return member.orElseThrow();
            }
            requireProjectDirectory(discovered.orElseThrow(), directory);
        }
        EffectiveManifest effective = standaloneLoader.effective(directory.resolve(MANIFEST));
        return new ManifestProject(
                directory,
                directory.resolve(MANIFEST),
                effective,
                adapter.adapt(effective),
                Optional.empty());
    }

    /**
     * Coverage floors for one project. A member's floors are the effective maximum of the workspace
     * root's floors and its own (design §4.5, Coverage).
     */
    public CoverageSettings coverageFloors(Path projectDirectory) {
        return adapter.coverage(project(projectDirectory).effective());
    }

    /**
     * Tasks and aliases for one project. A member sees the workspace root's commands merged with its
     * own (design §4.5, Tasks and aliases).
     */
    public CommandConfig commands(Path projectDirectory) {
        return ManifestCommandConfigAdapter.effective(
                project(projectDirectory).effective().project().shared().commands());
    }

    private Optional<ManifestProject> member(DiscoveredWorkspace discovered, Path directory) {
        Workspace workspace = workspaceLoader.adapt(discovered);
        for (WorkspaceMember member : workspace.members()) {
            if (!member.directory().toAbsolutePath().normalize().equals(directory)) {
                continue;
            }
            EffectiveManifest effective = discovered.effective()
                    .members()
                    .get(new WorkspaceMemberPath(member.path()));
            if (effective == null) {
                throw new WorkspaceConfigException(
                        "Workspace member `" + member.path() + "` has no effective manifest.");
            }
            return Optional.of(new ManifestProject(
                    directory,
                    directory.resolve(MANIFEST),
                    effective,
                    member.config(),
                    Optional.of(workspace.root())));
        }
        return Optional.empty();
    }

    /**
     * A virtual workspace root carries no project of its own (design §4.2), so a project command
     * started there has nothing to build.
     */
    private static void requireProjectDirectory(DiscoveredWorkspace discovered, Path directory) {
        if (!discovered.root().equals(directory)) {
            return;
        }
        throw new WorkspaceConfigException(
                "Workspace root " + directory.resolve(MANIFEST) + " declares no [project]. "
                        + "Run the command with --workspace, or run it from a member directory.");
    }
}
