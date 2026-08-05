package sh.zolt.workspace.state;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WorkspaceStateCodec {
    private static final String VERSION = "2";

    public String format(WorkspaceState state) {
        StringBuilder payload = new StringBuilder();
        state.members().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> member(payload, entry.getKey(), entry.getValue()));
        return "version=" + VERSION + "\n"
                + "checksum=" + WorkspaceHash.text(payload.toString()) + "\n"
                + payload;
    }

    public Optional<WorkspaceState> parse(String content) {
        try {
            int firstBreak = content.indexOf('\n');
            int secondBreak = content.indexOf('\n', firstBreak + 1);
            if (firstBreak < 0
                    || secondBreak < 0
                    || !content.substring(0, firstBreak).equals("version=" + VERSION)) {
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
            Map<String, WorkspaceMemberState> members = new LinkedHashMap<>();
            for (String line : payload.lines().toList()) {
                List<String> values = fields(line);
                if (values.size() != 14 || !"member".equals(values.getFirst())) {
                    return Optional.empty();
                }
                String member = decode(values.get(1));
                WorkspaceMemberState previous = members.put(member, state(values));
                if (previous != null) {
                    return Optional.empty();
                }
            }
            return Optional.of(new WorkspaceState(members));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static void member(
            StringBuilder payload,
            String member,
            WorkspaceMemberState state) {
        List<String> values = List.of(
                member,
                state.configDigest(),
                state.toolchainDigest(),
                state.mainSourceTreeDigest(),
                state.resourceTreeDigest(),
                state.generatedInputDigest(),
                state.mainCompileKey(),
                state.mainOutputManifestDigest(),
                state.publicAbiDigest(),
                state.packagePrivateAbiDigest(),
                state.testCompileKey(),
                state.testResourceTreeDigest(),
                state.testOutputManifestDigest());
        payload.append("member");
        values.forEach(value -> payload.append('\t').append(encode(value)));
        payload.append('\n');
    }

    private static WorkspaceMemberState state(List<String> values) {
        List<String> decoded = values.subList(2, values.size()).stream()
                .map(WorkspaceStateCodec::decode)
                .toList();
        return new WorkspaceMemberState(
                decoded.get(0),
                decoded.get(1),
                decoded.get(2),
                decoded.get(3),
                decoded.get(4),
                decoded.get(5),
                decoded.get(6),
                decoded.get(7),
                decoded.get(8),
                decoded.get(9),
                decoded.get(10),
                decoded.get(11));
    }

    private static List<String> fields(String line) {
        List<String> fields = new ArrayList<>();
        for (String field : line.split("\\t", -1)) {
            fields.add(field);
        }
        return fields;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(
                Base64.getUrlDecoder().decode(value),
                StandardCharsets.UTF_8);
    }
}
