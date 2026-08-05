package sh.zolt.workspace.state;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Row encoding shared by the member and file halves of the workspace state file. */
final class WorkspaceStateFields {
    private WorkspaceStateFields() {
    }

    static void row(StringBuilder payload, String tag, List<String> values) {
        payload.append(tag);
        values.forEach(value -> payload.append('\t').append(encode(value)));
        payload.append('\n');
    }

    static List<String> fields(String line) {
        return List.of(line.split("\\t", -1));
    }

    static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static long number(String value) {
        try {
            return Long.parseLong(decode(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Workspace state holds a non-numeric field.", exception);
        }
    }
}
