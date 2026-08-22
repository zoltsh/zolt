package sh.zolt.toml.manifest.adapter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.error.ActionableError;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.adapter.ProjectConfigCoverage;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.effective.EffectiveManifest;
import sh.zolt.manifest.effective.EffectiveManifestComposer;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.project.CoverageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.manifest.ZoltManifestParser;

/**
 * Loads one standalone {@code zolt.toml} written in the final manifest language and projects it onto
 * the legacy {@link ProjectConfig} the build engine consumes.
 *
 * <p>This loader parses only the final language; the tree contains no parser for any other
 * (design §21.1). Rejected pre-release spellings surface as ordinary unknown-field or unknown-value
 * diagnostics with no compatibility hints (design §21, Phase 2).
 */
public final class ManifestProjectConfigLoader {
    private static final String MANIFEST = "zolt.toml";

    private final ZoltManifestParser parser;
    private final EffectiveManifestComposer composer;
    private final EffectiveProjectConfigAdapter adapter;
    private final EnclosingWorkspaceLocator workspaces;

    public ManifestProjectConfigLoader() {
        this(new ZoltManifestParser(), new EffectiveManifestComposer(), new EffectiveProjectConfigAdapter());
    }

    public ManifestProjectConfigLoader(
            ZoltManifestParser parser,
            EffectiveManifestComposer composer,
            EffectiveProjectConfigAdapter adapter) {
        this.parser = parser;
        this.composer = composer;
        this.adapter = adapter;
        this.workspaces = new EnclosingWorkspaceLocator(parser);
    }

    /** Loads and adapts the manifest at {@code path} with no workspace context. */
    public ProjectConfig load(Path path) {
        return load(read(path));
    }

    /**
     * Loads and adapts the project in {@code projectDirectory}, composing it as a workspace member
     * when a workspace encloses it (design §4.5 "Command discovery"). This is the entry point for
     * every command that reads "the project here": a member manifest may legally omit inherited
     * identity, spell {@code workspace = true}, or reference a root-owned alias, so composing it
     * standalone rejects manifests that are valid.
     */
    public ProjectConfig loadProject(Path projectDirectory) {
        Optional<EnclosingWorkspaceLocator.Membership> located = workspaces.locate(projectDirectory);
        if (located.isEmpty()) {
            return adapter.adapt(effective(manifestPath(projectDirectory)));
        }
        EnclosingWorkspaceLocator.Membership membership = located.orElseThrow();
        WorkspaceMemberPath path = membership.memberPath();
        EffectiveWorkspace workspace = composeWorkspace(membership);
        return adapter.adapt(
                member(workspace, path),
                EffectiveProjectConfigAdapter.workspacePaths(workspace, path));
    }

    /**
     * The effective view of the project in {@code projectDirectory}: composed as a workspace member
     * when a workspace encloses it, and standalone otherwise. A root-project workspace composes as
     * its own {@code .} member (design §4.4).
     */
    public EffectiveManifest effectiveProject(Path projectDirectory) {
        Optional<EnclosingWorkspaceLocator.Membership> located = workspaces.locate(projectDirectory);
        if (located.isEmpty()) {
            return effective(manifestPath(projectDirectory));
        }
        EnclosingWorkspaceLocator.Membership membership = located.orElseThrow();
        return member(composeWorkspace(membership), membership.memberPath());
    }

    /**
     * Composes the whole workspace, not just the one member: a {@code workspace = true} dependency
     * resolves by effective member identity across the graph (design §9.8), so the sibling members
     * must be composed for the consumer's own view to be complete.
     */
    private EffectiveWorkspace composeWorkspace(EnclosingWorkspaceLocator.Membership membership) {
        try {
            return composer.composeWorkspace(membership.root(), workspaces.members(membership));
        } catch (IllegalArgumentException exception) {
            throw new ZoltConfigException(
                    "Invalid effective workspace at "
                            + membership.workspaceRoot().resolve(MANIFEST) + ": "
                            + exception.getMessage());
        }
    }

    private static EffectiveManifest member(EffectiveWorkspace workspace, WorkspaceMemberPath path) {
        EffectiveManifest member = workspace.members().get(path);
        if (member == null) {
            throw new ZoltConfigException(
                    "Workspace member `" + path + "` has no effective manifest.");
        }
        return member;
    }

    /** The root of the workspace enclosing {@code projectDirectory}, when the directory is a member. */
    public Optional<Path> enclosingWorkspaceRoot(Path projectDirectory) {
        return workspaces.locate(projectDirectory)
                .map(EnclosingWorkspaceLocator.Membership::workspaceRoot);
    }

    private static Path manifestPath(Path projectDirectory) {
        Objects.requireNonNull(projectDirectory, "Project directory is required.");
        Path directory = projectDirectory.toAbsolutePath().normalize();
        return Files.isRegularFile(directory) ? directory : directory.resolve(MANIFEST);
    }

    /** Parses and adapts already-captured manifest bytes. */
    public ProjectConfig load(String source) {
        return adapter.adapt(effective(source));
    }

    /** Parses {@code source} into its final document without composing an effective view. */
    public ZoltManifestDocument document(String source) {
        Objects.requireNonNull(source, "Manifest source is required.");
        return parser.parse(source);
    }

    /** Parses the manifest at {@code path} into its final document. */
    public ZoltManifestDocument document(Path path) {
        return document(read(path));
    }

    /**
     * Reads only the {@code [coverage]} floors, returning {@link CoverageSettings#none()} when the
     * file is absent so callers can treat "no config" as "no floors". This replaces the legacy
     * {@code parseCoverageFloors} entry point; the final field names are {@code line}, {@code branch},
     * {@code instruction}, and {@code method} (design §10.10).
     */
    public CoverageSettings coverageFloors(Path path) {
        if (!Files.exists(path)) {
            return CoverageSettings.none();
        }
        return coverageFloors(read(path));
    }

    /**
     * Reads the {@code [coverage]} floors from already-captured manifest bytes. The floors are read
     * exactly as authored so a virtual workspace root, which carries {@code [coverage]} but no
     * {@code [project]} to compose, is a valid input.
     */
    public CoverageSettings coverageFloors(String source) {
        return ProjectConfigCoverage.authored(document(source).authored().build().coverage());
    }

    /** Parses and composes the manifest at {@code path} into its effective standalone view. */
    public EffectiveManifest effective(Path path) {
        return effective(read(path));
    }

    /** Parses and composes {@code source} into its effective standalone view. */
    public EffectiveManifest effective(String source) {
        Objects.requireNonNull(source, "Manifest source is required.");
        ZoltManifestDocument document = parser.parse(source);
        try {
            return composer.composeStandalone(document.authored());
        } catch (IllegalArgumentException exception) {
            throw new ZoltConfigException(exception.getMessage());
        }
    }

    /**
     * Parses a workspace root and one of its members and composes that member's effective view
     * (design §4.5). {@code memberPath} is the member's workspace-relative path; the root-project
     * member {@code "."} reuses the root document, so {@code memberSource} is then the root source.
     */
    public EffectiveManifest effectiveWorkspaceMember(
            String rootSource,
            String memberSource,
            String memberPath) {
        Objects.requireNonNull(rootSource, "Workspace root manifest source is required.");
        Objects.requireNonNull(memberSource, "Workspace member manifest source is required.");
        WorkspaceMemberPath path = new WorkspaceMemberPath(memberPath);
        AuthoredManifest root = parser.parse(rootSource).authored();
        AuthoredManifest member = path.value().equals(".")
                ? root
                : parser.parse(memberSource).authored();
        try {
            return composer.composeWorkspaceMember(root, path, member);
        } catch (IllegalArgumentException exception) {
            throw new ZoltConfigException(exception.getMessage());
        }
    }

    private static String read(Path path) {
        Objects.requireNonNull(path, "Manifest path is required.");
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new ZoltConfigException(ActionableError.of(
                    "Could not read zolt.toml at " + path + ".",
                    "Check that the file exists and is readable.",
                    exception));
        }
    }
}
