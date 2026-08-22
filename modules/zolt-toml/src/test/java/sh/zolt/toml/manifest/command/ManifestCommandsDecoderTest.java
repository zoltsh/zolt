package sh.zolt.toml.manifest.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestCommandsTestSupport.decodeCommands;
import static sh.zolt.toml.manifest.ManifestCommandsTestSupport.decodeCommandsWithNullIndex;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.toml.ZoltConfigException;

final class ManifestCommandsDecoderTest {
    @Test
    void preservesWholeDomainOmissionAndEveryExplicitEmptyCollectionForm() {
        assertTrue(decode("").isEmpty());

        for (String source : List.of(
                "[tasks]\n",
                "tasks = {}\n",
                "[aliases]\n",
                "aliases = {}\n",
                "[tasks]\n[aliases]\n")) {
            AuthoredCommands commands = decode(source).orElseThrow();
            assertTrue(commands.tasks().isEmpty());
            assertTrue(commands.aliases().isEmpty());
            assertThrows(UnsupportedOperationException.class, commands.tasks()::clear);
            assertThrows(UnsupportedOperationException.class, commands.aliases()::clear);
        }
    }

    @Test
    void composesSortedImmutableCollectionsWithoutDefaultsOrLegacyCatalogRules() {
        AuthoredCommands commands = decode("""
                [tasks.zeta]
                run = ["zolt", "run"]

                [tasks.alpha]
                run = ["tool"]

                [aliases]
                zeta-alias = ["release-index", "--format", "json"]
                alpha-alias = ["task", "zeta"]
                """).orElseThrow();

        assertEquals(
                List.of("alpha", "zeta"),
                commands.tasks().keySet().stream().map(LocalId::value).toList());
        assertEquals(
                List.of("alpha-alias", "zeta-alias"),
                commands.aliases().keySet().stream().map(LocalId::value).toList());
        assertEquals(List.of("zolt", "run"), commands.tasks().get(id("zeta")).run());
        assertEquals(
                List.of("release-index", "--format", "json"),
                commands.aliases().get(id("zeta-alias")).argv());
        assertEquals(id("task"), commands.aliases().get(id("alpha-alias")).target());
        assertThrows(UnsupportedOperationException.class, commands.tasks()::clear);
        assertThrows(UnsupportedOperationException.class, commands.aliases()::clear);
    }

    @Test
    void decodesTasksBeforeAliasesRegardlessOfTomlOrder() {
        ZoltConfigException failure = assertFailure("""
                [aliases]
                ci = ["not-built-in"]

                [tasks.zeta]
                run = [" "]
                """, "tasks.zeta.run[0]", "Task executable must not be blank");
        assertFalse(failure.getMessage().contains("aliases.ci"), failure.getMessage());
    }

    @Test
    void validatesCollisionsInAliasSourceOrderBeforeFinalMapSorting() {
        for (String source : List.of(
                """
                [tasks.alpha]
                run = ["tool"]
                [tasks.zeta]
                run = ["tool"]
                [aliases]
                zeta = ["check"]
                alpha = ["not-built-in"]
                """,
                "tasks = { alpha = { run = [\"tool\"] }, zeta = { run = [\"tool\"] } }\n"
                        + "aliases = { zeta = [\"check\"], alpha = [\"not-built-in\"] }\n")) {
            ZoltConfigException failure = assertFailure(
                    source,
                    "Invalid value for `aliases.zeta`",
                    "Command ID `zeta` cannot be both a task and an alias");
            assertFalse(failure.getMessage().contains("aliases.alpha"), failure.getMessage());
        }
    }

    @Test
    void validatesTheTargetBeforeCollisionAndCollisionBeforeLaterArguments() {
        assertFailure("""
                [tasks.ci]
                run = ["tool"]
                [aliases]
                ci = ["not-built-in"]
                """, "aliases.ci[0]", "is not a built-in Zolt command");

        String nulEscape = "\\" + "u0000";
        ZoltConfigException failure = assertFailure(
                "[tasks.ci]\nrun = [\"tool\"]\n[aliases]\n"
                        + "ci = [\"check\", \"" + nulEscape + "\"]\n",
                "Invalid value for `aliases.ci`",
                "Command ID `ci` cannot be both a task and an alias");
        assertFalse(failure.getMessage().contains("[1]"), failure.getMessage());
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decodeCommandsWithNullIndex());
    }

    private static Optional<AuthoredCommands> decode(String source) {
        return decodeCommands(source);
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
