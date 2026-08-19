package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GeneratedProcessToolTest {
    @Test
    void processToolRequiresAnExactSelfProbeAndExplicitAcknowledgement() {
        ArrayList<String> command = new ArrayList<>(List.of("npm", "--version"));
        AuthoredGeneratedTool.Process tool = new AuthoredGeneratedTool.Process(
                new GeneratedProcessBinary("npm"),
                command,
                new GeneratedVersionExpectation(">=10 <11"),
                true);
        command.clear();

        assertEquals(List.of("npm", "--version"), tool.versionCommand());
        assertThrows(UnsupportedOperationException.class, () -> tool.versionCommand().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredGeneratedTool.Process(
                        new GeneratedProcessBinary("npm"),
                        List.of("node", "--version"),
                        new GeneratedVersionExpectation(">=10"),
                        true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredGeneratedTool.Process(
                        new GeneratedProcessBinary("npm"),
                        List.of("npm", "--version"),
                        new GeneratedVersionExpectation(">=10"),
                        false));
    }

    @Test
    void bareBinaryRejectsPathsWhitespaceAndShellSyntax() {
        assertEquals("npm.cmd", new GeneratedProcessBinary("npm.cmd").value());
        for (String value : List.of(
                "/usr/bin/npm", "bin/npm", "bin\\npm", "C:npm", "npm tool", "npm;whoami")) {
            assertThrows(IllegalArgumentException.class, () -> new GeneratedProcessBinary(value), value);
        }
    }

    @Test
    void versionExpectationUsesOnlyTheFrozenNumericComparatorGrammar() {
        for (String value : List.of(">=10 <11", "==1.2.3", "!=2", "=3.0", "<=4 >3.5")) {
            assertEquals(value, new GeneratedVersionExpectation(value).value());
        }
        for (String value : List.of("", "10", "^10", ">= 10", ">=10 || <11", ">=10\t<11", ">=v10")) {
            assertThrows(IllegalArgumentException.class, () -> new GeneratedVersionExpectation(value), value);
        }
    }
}
