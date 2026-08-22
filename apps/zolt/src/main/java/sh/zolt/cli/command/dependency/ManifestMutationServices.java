package sh.zolt.cli.command.dependency;

import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.AtomicManifestWriter;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.toml.manifest.edit.ManifestSourceEditor;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The final-language read/edit/write trio every source-safe mutation command shares.
 *
 * <p>Reads parse only the final manifest language and keep the exact captured source beside the
 * authored model; edits go through {@link ManifestSourceEditor}, which patches only schema-declared
 * mutable entries and fails closed rather than regenerating a file; writes replace the manifest
 * atomically and only while the captured bytes are still on disk (design §18.5, §19).
 */
public final class ManifestMutationServices {
    private final ManifestProjectConfigLoader loader;
    private final ManifestSourceEditor editor;

    public ManifestMutationServices() {
        this(new ManifestProjectConfigLoader(), new ManifestSourceEditor());
    }

    public ManifestMutationServices(ManifestProjectConfigLoader loader, ManifestSourceEditor editor) {
        this.loader = loader;
        this.editor = editor;
    }

    /** Captures the manifest at {@code path} as source plus authored model. */
    public ZoltManifestDocument document(Path path) {
        return loader.document(path);
    }

    /** Captures already-read manifest bytes as source plus authored model. */
    public ZoltManifestDocument document(String source) {
        return loader.document(source);
    }

    /** Applies one authored delta to {@code original} without touching unrelated source. */
    public ZoltManifestDocument edit(ZoltManifestDocument original, AuthoredManifest requested) {
        return editor.edit(original, requested);
    }

    /**
     * The authored manifest of the workspace root above {@code projectDirectory}, when a workspace
     * expanded that directory into a member (design §4.5). Mutation commands read it to see the
     * configuration a member may reference but must not redeclare; it never changes where an edit is
     * written. A workspace root asked about itself gets no second copy of its own manifest.
     */
    Optional<AuthoredManifest> workspaceRootManifest(Path projectDirectory) {
        Path directory = projectDirectory.toAbsolutePath().normalize();
        return loader.enclosingWorkspaceRoot(directory)
                .filter(root -> !root.equals(directory))
                .map(root -> loader.document(root.resolve("zolt.toml")).authored());
    }

    /** The standalone project view of edited manifest bytes, used to stage a resolve. */
    public ProjectConfig standaloneConfig(String source) {
        return loader.load(source);
    }

    /** Replaces {@code path} atomically, refusing when its captured bytes already changed. */
    public void writePrepared(Path path, String original, String edited) {
        AtomicManifestWriter.writePrepared(path, original, edited);
    }
}
