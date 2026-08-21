package sh.zolt.toml.manifest.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestCommandsTestSupport.decodeAliases;
import static sh.zolt.toml.manifest.ManifestCommandsTestSupport.decodeAliasesWithNullIndex;
import static sh.zolt.toml.manifest.ManifestCommandsTestSupport.decodeAliasesWithNullObserver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredAlias;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestSymbols;

final class ManifestAliasesDecoderTest {
    @Test
    void preservesOmissionAndBothExplicitEmptyCollectionForms() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("[tasks]\n").isEmpty());

        for (String source : List.of("[aliases]\n", "aliases = {}\n")) {
            Map<LocalId, AuthoredAlias> aliases = decode(source).orElseThrow();
            assertTrue(aliases.isEmpty());
            assertThrows(UnsupportedOperationException.class, aliases::clear);
        }
    }

    @Test
    void decodesEntriesIntoASortedDeeplyImmutableMapWithAuthoredArgvOrder() {
        Map<LocalId, AuthoredAlias> aliases = decode("""
                [aliases]
                zeta = ["task", "release-notes", "", "VALUE=literal", "$(not-a-shell)"]
                alpha = ["check", "--context", "ci"]
                """).orElseThrow();

        assertEquals(
                List.of("alpha", "zeta"),
                aliases.keySet().stream().map(LocalId::value).toList());
        assertEquals(
                List.of("task", "release-notes", "", "VALUE=literal", "$(not-a-shell)"),
                aliases.get(id("zeta")).argv());
        assertEquals(id("task"), aliases.get(id("zeta")).target());
        assertThrows(UnsupportedOperationException.class, aliases::clear);
        assertThrows(UnsupportedOperationException.class, aliases.get(id("zeta")).argv()::clear);
    }

    @Test
    void acceptsEveryExactSchemaBuiltInAsATarget() {
        for (String target : FinalManifestSymbols.builtInCommandNames()) {
            AuthoredAlias alias = decode(
                            "[aliases]\ncustom = [\"" + target + "\"]\n")
                    .orElseThrow()
                    .get(id("custom"));
            assertEquals(target, alias.target().value());
        }
    }

    @Test
    void validatesExplicitAndInlineEntriesInSourceOrderBeforeSorting() {
        for (String source : List.of(
                "[aliases]\nzeta = [\"not-built-in\"]\nalpha = [\"also-invalid\"]\n",
                "aliases = { zeta = [\"not-built-in\"], alpha = [\"also-invalid\"] }\n")) {
            ZoltConfigException failure = assertFailure(
                    source,
                    "aliases.zeta[0]",
                    "Alias `zeta` target `not-built-in` is not a built-in Zolt command");
            assertFalse(failure.getMessage().contains("alpha"), failure.getMessage());
        }
    }

    @Test
    void anchorsAliasModelFailuresToTheirCausalArguments() {
        String nulEscape = "\\" + "u0000";
        for (FailureFixture fixture : List.of(
                new FailureFixture("[]", "aliases.ci", "Alias arguments must not be empty"),
                new FailureFixture("[\" \"]", "aliases.ci[0]", "Alias target must not be blank"),
                new FailureFixture(
                        "[\"Bad_Id\"]", "aliases.ci[0]", "Invalid local ID"),
                new FailureFixture(
                        "[\"not-built-in\"]",
                        "aliases.ci[0]",
                        "is not a built-in Zolt command"),
                new FailureFixture(
                        "[\"check\", \"" + nulEscape + "\"]",
                        "aliases.ci[1]",
                        "Alias arguments must not contain NUL"))) {
            assertFailure(
                    "[aliases]\nci = " + fixture.value() + "\n",
                    fixture.path(),
                    fixture.detail());
        }
    }

    @Test
    void validatesTheTargetBeforeReadingLaterArguments() {
        String nulEscape = "\\" + "u0000";
        ZoltConfigException failure = assertFailure(
                "[aliases]\nci = [\"not-built-in\", \"" + nulEscape + "\"]\n",
                "aliases.ci[0]",
                "is not a built-in Zolt command");
        assertFalse(failure.getMessage().contains("[1]"), failure.getMessage());
    }

    @Test
    void leavesStructuralAndLegacyFailuresToShapeValidation() {
        for (Map.Entry<String, String> fixture : List.of(
                Map.entry("[aliases]\nBad_Id = [\"check\"]\n", "Invalid dynamic key"),
                Map.entry("[aliases]\nbuild = [\"check\"]\n", "is reserved"),
                Map.entry("[aliases]\nci = \"check\"\n", "expected string array"),
                Map.entry("[aliases.ci]\ntarget = \"check\"\n", "must be authored as an assignment"),
                Map.entry(
                        "[commands.aliases]\nci = [\"check\"]\n",
                        "Unknown manifest section"))) {
            ZoltConfigException failure = assertThrows(
                    ZoltConfigException.class, () -> decode(fixture.getKey()));
            assertTrue(failure.getMessage().contains(fixture.getValue()), failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decodeAliasesWithNullIndex());
        assertThrows(NullPointerException.class, () -> decodeAliasesWithNullObserver("[aliases]\n"));
    }

    private static Optional<Map<LocalId, AuthoredAlias>> decode(String source) {
        return decodeAliases(source);
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

    private record FailureFixture(String value, String path, String detail) {
    }
}
