package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.GeneratedProcessBinary;
import sh.zolt.manifest.GeneratedVersionExpectation;

final class GeneratedProcessToolTest {
    @Test
    void processToolRequiresAnExactSelfProbeAndExplicitAcknowledgement() {
        ArrayList<String> command = new ArrayList<>(List.of("npm", "--version"));
        AuthoredGeneratedTool.Process tool = new AuthoredGeneratedTool.Process(
                new GeneratedProcessBinary("npm"),
                command,
                Optional.of(new GeneratedVersionExpectation(">=10 <11")),
                true);
        command.clear();

        assertEquals(List.of("npm", "--version"), tool.versionCommand());
        assertThrows(UnsupportedOperationException.class, () -> tool.versionCommand().clear());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredGeneratedTool.Process(
                        new GeneratedProcessBinary("npm"),
                        List.of("node", "--version"),
                        Optional.of(new GeneratedVersionExpectation(">=10")),
                        true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredGeneratedTool.Process(
                        new GeneratedProcessBinary("npm"),
                        List.of("npm", "--version"),
                        Optional.of(new GeneratedVersionExpectation(">=10")),
                        false));

        assertEquals(
                List.of("npm", ""),
                new AuthoredGeneratedTool.Process(
                                new GeneratedProcessBinary("npm"),
                                List.of("npm", ""),
                                Optional.empty(),
                                true)
                        .versionCommand());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredGeneratedTool.Process(
                        new GeneratedProcessBinary("npm"),
                        List.of("npm", "bad\0argument"),
                        Optional.of(new GeneratedVersionExpectation(">=10")),
                        true));
    }

    @Test
    void bareBinaryRejectsPathsWhitespaceAndShellSyntax() {
        assertEquals("npm.cmd", new GeneratedProcessBinary("npm.cmd").value());
        for (String value : List.of(
                "/usr/bin/npm", "bin/npm", "bin\\npm", "C:npm", "npm tool", "npm;whoami",
                "npm%PATH%", "npm^cmd")) {
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
