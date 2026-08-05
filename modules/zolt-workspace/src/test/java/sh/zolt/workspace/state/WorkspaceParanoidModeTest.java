package sh.zolt.workspace.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorkspaceParanoidModeTest {
    @Test
    void theSwitchIsOffUnlessTheEnvironmentTurnsItOn() {
        assertFalse(WorkspaceParanoidMode.enabled(Map.of()));
        assertFalse(WorkspaceParanoidMode.enabled(Map.of("ZOLT_WORKSPACE_PARANOID", "")));
        assertFalse(WorkspaceParanoidMode.enabled(Map.of("ZOLT_WORKSPACE_PARANOID", "0")));
        assertFalse(WorkspaceParanoidMode.enabled(Map.of("ZOLT_WORKSPACE_PARANOID", "no")));
    }

    @Test
    void theSwitchAcceptsTheSameValuesTheRestOfZoltDoes() {
        assertTrue(WorkspaceParanoidMode.enabled(Map.of("ZOLT_WORKSPACE_PARANOID", "1")));
        assertTrue(WorkspaceParanoidMode.enabled(Map.of("ZOLT_WORKSPACE_PARANOID", "true")));
        assertTrue(WorkspaceParanoidMode.enabled(Map.of("ZOLT_WORKSPACE_PARANOID", "TRUE")));
    }
}
