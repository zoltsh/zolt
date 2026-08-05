package sh.zolt.workspace.state;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The file half of the workspace state file: one {@code file} row per tracked input, carrying the
 * stat that was true when the content hash beside it was taken.
 *
 * <p>Rows are written in path order so a table whose contents did not change formats to identical
 * bytes, which is what lets the store skip rewriting an unchanged state.
 */
final class WorkspaceFileStateCodec {
    private static final String TAG = "file";
    private static final int FIELDS = 8;

    void format(StringBuilder payload, WorkspaceFileState state) {
        state.files().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> row(payload, entry.getValue()));
    }

    boolean isFileRow(List<String> values) {
        return !values.isEmpty() && TAG.equals(values.getFirst());
    }

    /** A row, or empty when the row is malformed — which fails the whole parse closed. */
    Optional<WorkspaceFileRecord> parse(List<String> values) {
        if (values.size() != FIELDS) {
            return Optional.empty();
        }
        Optional<WorkspaceFileKind> kind =
                WorkspaceFileKind.fromId(WorkspaceStateFields.decode(values.get(2)));
        if (kind.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WorkspaceFileRecord(
                WorkspaceStateFields.decode(values.get(1)),
                kind.orElseThrow(),
                WorkspaceStateFields.decode(values.get(3)),
                WorkspaceStateFields.number(values.get(4)),
                WorkspaceStateFields.number(values.get(5)),
                WorkspaceStateFields.decode(values.get(6)),
                WorkspaceStateFields.decode(values.get(7))));
    }

    static WorkspaceFileState state(Map<String, WorkspaceFileRecord> files) {
        return new WorkspaceFileState(0L, new LinkedHashMap<>(files));
    }

    private static void row(StringBuilder payload, WorkspaceFileRecord record) {
        WorkspaceStateFields.row(
                payload,
                TAG,
                List.of(
                        record.path(),
                        record.kind().id(),
                        record.member(),
                        Long.toString(record.size()),
                        Long.toString(record.modifiedNanos()),
                        record.fileKey(),
                        record.hash()));
    }
}
