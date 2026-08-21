package sh.zolt.explain.emit;

import sh.zolt.manifest.authored.AuthoredManifest;
import java.util.List;

/**
 * A draft Zolt workspace synthesized from a multi-module Maven reactor or Gradle multi-project build.
 *
 * <p>{@link #root()} is the root manifest: a virtual workspace carrying {@code [workspace]},
 * {@code [workspace.members]}, and the shared {@code [workspace.project]} identity every member
 * inherits. {@link #members()} are the per-module drafts, each tagged with the relative directory it
 * belongs in, so the renderer can label every document with its target {@code <path>/zolt.toml}.
 * {@link #notes()} are workspace-level review items (e.g. deps declared on the root aggregator that a
 * virtual workspace cannot carry).
 */
public record DraftWorkspace(AuthoredManifest root, List<Member> members, List<String> notes)
        implements DraftEmit {
    public DraftWorkspace {
        members = List.copyOf(members);
        notes = List.copyOf(notes);
    }

    /** One member of a draft workspace: its relative path plus the draft zolt.toml for that module. */
    public record Member(String path, DraftZoltToml draft) {
        public Member {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("A workspace member must have a non-blank relative path.");
            }
        }
    }
}
