package sh.zolt.workspace.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes {@code .zolt/workspace-state-v1}.
 *
 * <p>Version 3 adds the per-file table beside the per-member digests. Version 2 states are still
 * read — their member rows are exactly what version 3 writes — and simply arrive with an empty file
 * table, which makes the first command after an upgrade hash every input once and then persist a
 * version 3 state. Nothing recompiles for the migration: the member digests it decides from are
 * carried across unchanged.
 *
 * <p>Anything older, corrupt, or unrecognised parses to empty and is treated as no state at all.
 */
public final class WorkspaceStateCodec {
    private static final String VERSION = "3";
    private static final List<String> READABLE_VERSIONS = List.of("3", "2");
    private static final String MEMBER_TAG = "member";
    /** Version 2 wrote twelve digests per member; version 3 appends two more. */
    private static final int MINIMUM_MEMBER_FIELDS = 14;
    private static final int MEMBER_FIELDS = 2 + WorkspaceMemberState.DIGESTS;

    private final WorkspaceFileStateCodec files = new WorkspaceFileStateCodec();

    public String format(WorkspaceState state) {
        StringBuilder payload = new StringBuilder();
        state.members().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> member(payload, entry.getKey(), entry.getValue()));
        files.format(payload, state.files());
        return "version=" + VERSION + "\n"
                + "checksum=" + WorkspaceHash.text(payload.toString()) + "\n"
                + payload;
    }

    public Optional<WorkspaceState> parse(String content) {
        try {
            int firstBreak = content.indexOf('\n');
            int secondBreak = content.indexOf('\n', firstBreak + 1);
            if (firstBreak < 0 || secondBreak < 0 || !readable(content.substring(0, firstBreak))) {
                return Optional.empty();
            }
            String checksumLine = content.substring(firstBreak + 1, secondBreak);
            if (!checksumLine.startsWith("checksum=")) {
                return Optional.empty();
            }
            String payload = content.substring(secondBreak + 1);
            if (!checksumLine.substring("checksum=".length()).equals(WorkspaceHash.text(payload))) {
                return Optional.empty();
            }
            return rows(payload);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<WorkspaceState> rows(String payload) {
        Map<String, WorkspaceMemberState> members = new LinkedHashMap<>();
        Map<String, WorkspaceFileRecord> tracked = new LinkedHashMap<>();
        for (String line : payload.lines().toList()) {
            List<String> values = WorkspaceStateFields.fields(line);
            if (files.isFileRow(values)) {
                Optional<WorkspaceFileRecord> record = files.parse(values);
                if (record.isEmpty() || tracked.put(record.orElseThrow().path(), record.orElseThrow()) != null) {
                    return Optional.empty();
                }
                continue;
            }
            if (values.size() < MINIMUM_MEMBER_FIELDS
                    || values.size() > MEMBER_FIELDS
                    || !MEMBER_TAG.equals(values.getFirst())) {
                return Optional.empty();
            }
            if (members.put(WorkspaceStateFields.decode(values.get(1)), state(values)) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(new WorkspaceState(members, WorkspaceFileStateCodec.state(tracked)));
    }

    private static boolean readable(String versionLine) {
        return READABLE_VERSIONS.stream().anyMatch(version -> versionLine.equals("version=" + version));
    }

    private static void member(
            StringBuilder payload,
            String member,
            WorkspaceMemberState state) {
        List<String> values = new ArrayList<>();
        values.add(member);
        values.addAll(state.digests());
        WorkspaceStateFields.row(payload, MEMBER_TAG, values);
    }

    private static WorkspaceMemberState state(List<String> values) {
        return WorkspaceMemberState.of(values.subList(2, values.size()).stream()
                .map(WorkspaceStateFields::decode)
                .toList());
    }
}
