package sh.zolt.workspace.state;

import java.util.Optional;

/**
 * Which source set a tracked workspace file belongs to.
 *
 * <p>The kind is recorded beside the file so a sweep of one source set can replace exactly that
 * set's rows: a file the sweep no longer finds is a deleted file, and dropping its row is how the
 * table learns that. Kinds swept as whole trees are marked {@link #swept()}; the rest are single
 * files asked for by name, where absence from a command means "not looked at", not "gone".
 */
public enum WorkspaceFileKind {
    MAIN_SOURCE("main-source", true),
    MAIN_RESOURCE("main-resource", true),
    TEST_SOURCE("test-source", true),
    TEST_RESOURCE("test-resource", true),
    GENERATED_INPUT("generated-input", true),
    GENERATED_OUTPUT("generated-output", true),
    OUTPUT_RESOURCE("output-resource", false),
    CONFIG("config", false);

    private final String id;
    private final boolean swept;

    WorkspaceFileKind(String id, boolean swept) {
        this.id = id;
        this.swept = swept;
    }

    public String id() {
        return id;
    }

    /** Whether a command that reads this kind reads all of it, so unseen rows mean deleted files. */
    public boolean swept() {
        return swept;
    }

    static Optional<WorkspaceFileKind> fromId(String value) {
        for (WorkspaceFileKind kind : values()) {
            if (kind.id.equals(value)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
