package sh.zolt.workspace.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Non-semantic directory evidence for one membership question discovery actually asked.
 *
 * <p>Design §6.7 makes a newly created, deleted, or retyped member directory invalidate a captured
 * plan. The evidence is therefore scoped to the entries a pattern segment consulted — the exact
 * name for a literal segment, or every non-dot directory for {@code *} — and never to the whole
 * directory. Files and directories a command creates for itself, such as the authoritative
 * {@code zolt.lock}, a {@code .zolt} state directory, or a cache root inside the workspace, change
 * no membership answer and must not invalidate the plan that created them.
 */
public record WorkspaceDirectoryEvidence(Path directory, String selector, List<String> entries) {
    /** The selector every non-dot child directory answers. */
    public static final String WILDCARD = "*";

    public WorkspaceDirectoryEvidence {
        directory = Objects.requireNonNull(directory, "Evidence directory is required.")
                .toAbsolutePath()
                .normalize();
        selector = Objects.requireNonNull(selector, "Evidence selector is required.");
        entries = List.copyOf(Objects.requireNonNull(entries, "Evidence entries are required."));
    }

    boolean wildcard() {
        return WILDCARD.equals(selector);
    }
}
