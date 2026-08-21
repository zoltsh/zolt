package sh.zolt.explain.emit;

import sh.zolt.manifest.authored.AuthoredManifest;
import java.util.List;

/**
 * A draft zolt.toml synthesized from a static migration audit.
 *
 * <p>{@link #manifest()} is the mappable part of the audit as an authored manifest in the final
 * language; the renderer serializes it through the canonical writer, so the emitted document is
 * sparse by construction (design §5.1). {@link #notes()} are ambiguous or unmappable inspection
 * facts the audit could not safely guess; the renderer emits them as {@code #}-prefixed comment
 * lines so the adopter can resolve them by hand before use.
 *
 * <p>{@link #suggestCompilerJdkApiHost()} marks a host-platform-API candidate: the POM used
 * {@code source}/{@code target} below the build JDK, so the renderer adds a commented-out
 * {@code # jdkApi = "host"} suggestion under {@code [compiler]}. It stays commented because Zolt
 * defaults to the reproducible {@code --release} surface and host mode is opt-in only.
 *
 * <p>A single-project audit emits one {@code DraftZoltToml}; a multi-module reactor / multi-project
 * build emits a {@link DraftWorkspace} instead. Both are {@link DraftEmit}s.
 */
public record DraftZoltToml(
        AuthoredManifest manifest,
        List<String> notes,
        boolean suggestCompilerJdkApiHost) implements DraftEmit {
    public DraftZoltToml(AuthoredManifest manifest, List<String> notes) {
        this(manifest, notes, false);
    }

    public DraftZoltToml {
        notes = List.copyOf(notes);
    }
}
