package sh.zolt.workspace.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorkspaceStateCodecTest {
    private final WorkspaceStateCodec codec = new WorkspaceStateCodec();

    @Test
    void stateRoundTripsDeterministicallyWithChecksum() {
        WorkspaceState state = new WorkspaceState(Map.of(
                "apps/api",
                memberState("api"),
                "modules/core",
                memberState("core")));

        String encoded = codec.format(state);
        WorkspaceState decoded = codec.parse(encoded).orElseThrow();

        assertEquals(state, decoded);
        assertEquals(encoded, codec.format(decoded));
        assertTrue(encoded.startsWith("version=2\nchecksum="));
    }

    @Test
    void corruptOrUnknownStateFailsClosed() {
        String encoded = codec.format(
                new WorkspaceState(Map.of("modules/core", memberState("core"))));

        String corrupt = encoded.substring(0, encoded.length() - 2) + "X\n";
        assertTrue(codec.parse(corrupt).isEmpty());
        assertTrue(codec.parse(encoded.replace("version=2", "version=999")).isEmpty());
        assertTrue(codec.parse("not-state").isEmpty());
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
