package sh.zolt.workspace.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorkspaceStateCodecTest {
    private final WorkspaceStateCodec codec = new WorkspaceStateCodec();

    @Test
    void stateRoundTripsDeterministicallyWithChecksum() {
        WorkspaceState state = new WorkspaceState(
                Map.of("apps/api", memberState("api"), "modules/core", memberState("core")),
                fileState("apps/api/src/main/java/Api.java", "modules/core/src/main/java/Core.java"));

        String encoded = codec.format(state);
        WorkspaceState decoded = codec.parse(encoded).orElseThrow();

        assertEquals(state, decoded);
        assertEquals(encoded, codec.format(decoded));
        assertTrue(encoded.startsWith("version=3\nchecksum="));
    }

    @Test
    void corruptOrUnknownStateFailsClosed() {
        String encoded = codec.format(
                new WorkspaceState(Map.of("modules/core", memberState("core"))));

        String corrupt = encoded.substring(0, encoded.length() - 2) + "X\n";
        assertTrue(codec.parse(corrupt).isEmpty());
        assertTrue(codec.parse(encoded.replace("version=3", "version=1")).isEmpty());
        assertTrue(codec.parse(encoded.replace("version=3", "version=999")).isEmpty());
        assertTrue(codec.parse("not-state").isEmpty());
    }

    /**
     * A version 2 state carries member rows and no file rows. It must still decode — its member
     * digests are what dirtiness is decided from, and discarding them would recompile the workspace
     * for a format change — and it must arrive with an empty file table so the first command after
     * the upgrade reads every input once and then writes a version 3 state.
     */
    @Test
    void versionTwoStateMigratesWithItsMemberRowsIntact() {
        WorkspaceState state = new WorkspaceState(
                Map.of("modules/core", memberState("core")),
                fileState("modules/core/src/main/java/Core.java"));

        WorkspaceState decoded = codec.parse(version2(codec.format(state))).orElseThrow();

        assertEquals(memberState("core"), decoded.member("modules/core").orElseThrow());
        assertEquals(Map.of(), decoded.files().files());
        assertTrue(codec.format(decoded).startsWith("version=3\n"));
    }

    /** Rewrites a version 3 state the way the version 2 codec would have written it. */
    private static String version2(String encoded) {
        StringBuilder payload = new StringBuilder();
        encoded.lines().skip(2).forEach(line -> {
            String[] fields = line.split("\t", -1);
            if ("member".equals(fields[0])) {
                payload.append(String.join("\t", Arrays.copyOfRange(fields, 0, 14))).append('\n');
            }
        });
        return "version=2\nchecksum=" + WorkspaceHash.text(payload.toString()) + "\n" + payload;
    }

    private static WorkspaceFileState fileState(String... paths) {
        Map<String, WorkspaceFileRecord> files = new LinkedHashMap<>();
        for (int index = 0; index < paths.length; index++) {
            String path = paths[index];
            files.put(
                    path,
                    new WorkspaceFileRecord(
                            path,
                            WorkspaceFileKind.MAIN_SOURCE,
                            path.substring(0, path.indexOf("/src/")),
                            100L + index,
                            1_700_000_000_000_000_000L + index,
                            "key-" + index,
                            WorkspaceHash.text(path)));
        }
        return new WorkspaceFileState(0L, files);
    }

    private static WorkspaceMemberState memberState(String value) {
        return new WorkspaceMemberState(
                "config-" + value,
                "toolchain-" + value,
                "source-" + value,
                "resource-" + value,
                "generated-" + value,
                "compile-" + value,
                "output-" + value,
                "public-" + value,
                "package-" + value,
                "test-" + value,
                "test-resource-" + value,
                "test-output-" + value);
    }
}
