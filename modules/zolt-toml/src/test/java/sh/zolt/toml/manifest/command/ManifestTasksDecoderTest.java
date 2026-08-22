package sh.zolt.toml.manifest.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestCommandsTestSupport.decodeTasks;
import static sh.zolt.toml.manifest.ManifestCommandsTestSupport.decodeTasksWithNullIndex;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredTask;
import sh.zolt.toml.ZoltConfigException;

final class ManifestTasksDecoderTest {
    @Test
    void preservesOmissionAndBothExplicitEmptyCollectionForms() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("[aliases]\n").isEmpty());

        for (String source : List.of("[tasks]\n", "tasks = {}\n")) {
            Map<LocalId, AuthoredTask> tasks = decode(source).orElseThrow();
            assertTrue(tasks.isEmpty());
            assertThrows(UnsupportedOperationException.class, tasks::clear);
        }
    }

    @Test
    void decodesNamedRowsIntoASortedDeeplyImmutableMapWithoutDefaults() {
        Map<LocalId, AuthoredTask> tasks = decode("""
                [tasks.zeta]
                env = { ZETA = "last", ALPHA = "" }
                cwd = "tools"
                run = ["zolt", "run", "", "--workspace"]
                description = "  Generate release notes.  "

                [tasks.alpha]
                run = ["python3", "-m", "http.server"]
                """).orElseThrow();

        assertEquals(
                List.of("alpha", "zeta"),
                tasks.keySet().stream().map(LocalId::value).toList());
        AuthoredTask zeta = tasks.get(id("zeta"));
        assertEquals(Optional.of("  Generate release notes.  "), zeta.description());
        assertEquals(List.of("zolt", "run", "", "--workspace"), zeta.run());
        assertEquals("tools", zeta.cwd().orElseThrow().value());
        assertEquals(
                List.of("ALPHA", "ZETA"),
                zeta.env().keySet().stream()
                        .map(EnvironmentVariableName::value)
                        .toList());
        assertEquals("", zeta.env().get(new EnvironmentVariableName("ALPHA")));
        assertEquals(Optional.empty(), tasks.get(id("alpha")).description());
        assertEquals(Optional.empty(), tasks.get(id("alpha")).cwd());
        assertTrue(tasks.get(id("alpha")).env().isEmpty());
        assertThrows(UnsupportedOperationException.class, tasks::clear);
        assertThrows(UnsupportedOperationException.class, zeta.run()::clear);
        assertThrows(UnsupportedOperationException.class, zeta.env()::clear);
    }

    @Test
    void validatesExplicitAndInlineRowsInSourceOrderBeforeSorting() {
        for (String source : List.of(
                """
                [tasks.zeta]
                description = " "
                run = ["zolt"]

                [tasks.alpha]
                run = []
                """,
                "tasks = { zeta = { description = \" \", run = [\"zolt\"] }, "
                        + "alpha = { run = [] } }\n")) {
            assertFailure(
                    source,
                    "tasks.zeta.description",
                    "Task description must not be blank");
        }
    }

    @Test
    void requiresRunAfterValidatingDescriptionInCanonicalFieldOrder() {
        ZoltConfigException missing = assertThrows(
                ZoltConfigException.class,
                () -> decode("[tasks.release-notes]\ndescription = \"notes\"\n"));
        assertTrue(missing.getMessage().contains("tasks.release-notes.run"), missing.getMessage());
        assertTrue(missing.getMessage().contains("Missing required manifest field"), missing.getMessage());
        assertNull(missing.getCause());

        String nulEscape = "\\" + "u0000";
        assertFailure(
                "[tasks.release-notes]\nenv = { BAD = \"" + nulEscape + "\" }\n"
                        + "description = \" \"\n",
                "tasks.release-notes.description",
                "Task description must not be blank");
        assertFailure(
                "[tasks.release-notes]\nenv = { BAD = \"" + nulEscape + "\" }\n"
                        + "run = [\" \" ]\n",
                "tasks.release-notes.run[0]",
                "Task executable must not be blank");
    }

    @Test
    void anchorsEmptyAndInvalidRunValuesToTheirCausalPositions() {
        assertFailure(
                "[tasks.release-notes]\nrun = []\n",
                "tasks.release-notes.run",
                "Task run arguments must not be empty");
        assertFailure(
                "[tasks.release-notes]\nrun = [\" \"]\n",
                "tasks.release-notes.run[0]",
                "Task executable must not be blank");

        String nulEscape = "\\" + "u0000";
        assertFailure(
                "[tasks.release-notes]\nrun = [\"zolt\", \"" + nulEscape + "\"]\n",
                "tasks.release-notes.run[1]",
                "Task run argument must not contain NUL");
    }

    @Test
    void anchorsEnvironmentValueFailuresToTheFieldAndCausalKey() {
        String nulEscape = "\\" + "u0000";
        ZoltConfigException failure = assertFailure(
                "[tasks.release-notes]\nrun = [\"zolt\"]\n"
                        + "env = { ZED = \"" + nulEscape + "\", ALPHA = \""
                        + nulEscape + "\" }\n",
                "tasks.release-notes.env",
                "Environment entry `ZED`",
                "Task environment value must not contain NUL");
        assertFalse(failure.getMessage().contains("ALPHA"), failure.getMessage());
    }

    @Test
    void leavesStructuralFailuresToShapeValidation() {
        for (Map.Entry<String, String> fixture : List.of(
                Map.entry("[tasks.release_notes]\nrun = [\"zolt\"]\n", "Invalid dynamic key"),
                Map.entry("[tasks.build]\nrun = [\"zolt\"]\n", "is reserved"),
                Map.entry("[tasks.release-notes]\n", "must not be empty"),
                Map.entry("[tasks.release-notes]\nrun = \"zolt\"\n", "expected string array"),
                Map.entry(
                        "[tasks.release-notes]\nrun = [\"zolt\"]\ncwd = \"../tools\"\n",
                        "Invalid manifest path"),
                Map.entry(
                        "[tasks.release-notes]\nrun = [\"zolt\"]\nenv = { BAD_NAME = 1 }\n",
                        "expected a string"),
                Map.entry(
                        "[tasks.release-notes]\nrun = [\"zolt\"]\nenv = { BAD-NAME = \"x\" }\n",
                        "Invalid environment-variable name"),
                Map.entry(
                        "[tasks.release-notes]\nrun = [\"zolt\"]\nenv = { PATH = \"x\", Path = \"y\" }\n",
                        "differ only by ASCII case"),
                Map.entry(
                        "[tasks.release-notes]\nrun = [\"zolt\"]\ncmd = [\"zolt\"]\n",
                        "Unknown manifest field"))) {
            ZoltConfigException failure = assertThrows(
                    ZoltConfigException.class, () -> decode(fixture.getKey()));
            assertTrue(failure.getMessage().contains(fixture.getValue()), failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decodeTasksWithNullIndex());
    }

    private static Optional<Map<LocalId, AuthoredTask>> decode(String source) {
        return decodeTasks(source);
    }

    private static ZoltConfigException assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }
}
