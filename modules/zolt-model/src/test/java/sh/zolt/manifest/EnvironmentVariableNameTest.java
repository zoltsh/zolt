package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class EnvironmentVariableNameTest {
    @Test
    void acceptsThePortableAsciiGrammarAndPreservesCase() {
        assertEquals("_", new EnvironmentVariableName("_").value());
        assertEquals("MAVEN_TOKEN_2", new EnvironmentVariableName("MAVEN_TOKEN_2").toString());
        assertEquals("mixedCase", new EnvironmentVariableName("mixedCase").value());
    }

    @Test
    void rejectsEveryValueOutsideThePortableGrammar() {
        for (String value : List.of("", "9TOKEN", "TOKEN-NAME", "TOKEN.NAME", " TOKEN", "TOKEN ",
                "TÖKEN", "TOKEN=secret", "TOKEN\0NAME", "TOKEN\nNAME")) {
            assertThrows(IllegalArgumentException.class, () -> new EnvironmentVariableName(value), value);
        }
        assertThrows(NullPointerException.class, () -> new EnvironmentVariableName(null));
    }
}
