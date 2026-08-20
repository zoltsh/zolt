package sh.zolt.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class ManifestShapeValidatorStructureTest {
    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator();

    @Test
    void acceptsGeneralDottedAndInlineParentTomlWithExactSourceEvidence() {
        String source = """
                project.name = "demo"
                build = { output = { root = "target" } }
                """;

        ValidatedManifestShape shape = validate(source);
        assertEquals(
                List.of("project.name", "build.output.root"),
                shape.fields().stream().map(field -> field.path().toString()).toList());
        ValidatedManifestField dotted = shape.fields().get(0);
        ValidatedManifestField inline = shape.fields().get(1);
        assertEquals(ManifestShapeOrigin.DIRECT_ASSIGNMENT, dotted.source().origin());
        assertEquals("project.name = \"demo\"", dotted.source().span().text(source));
        assertEquals(ManifestShapeOrigin.INLINE_PARENT, inline.source().origin());
        assertEquals("build = { output = { root = \"target\" } }", inline.source().span().text(source));
    }

    @Test
    void failsClosedWhenSyntaxCameFromDifferentSameLengthSource() {
        String parsed = "[project]\nname = \"one\"\n";
        String different = "[project]\nname = \"two\"\n";
        ManifestSyntax syntax = parser.parse(parsed);

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> validator.validate(different, syntax));

        assertTrue(failure.getMessage().contains("does not match its parsed syntax"));
    }

    @Test
    void resolvesExactStaticPathsBeforeDynamicMatches() {
        validate("""
                [repositories]
                central = false

                [dependencies.api]
                """);
    }

    @ParameterizedTest
    @MethodSource("emptyNamedCollectionParents")
    void acceptsEveryExplicitEmptyNamedCollectionParent(String path) {
        validate("[" + path + "]\n");
    }

    static Stream<String> emptyNamedCollectionParents() {
        return Stream.of(
                "project.developers",
                "credentials",
                "dependencies.license-exceptions",
                "generated.tools",
                "generated.presets",
                "generated.main",
                "generated.test",
                "test.suites",
                "publish.repositories",
                "tasks");
    }

    @ParameterizedTest
    @MethodSource("invalidEmptyTables")
    void rejectsExplicitEmptyNoncollectionTables(String source, String expected) {
        assertFailureContains(source, expected);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidEmptyTables() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("[repositories]\n", "must not be empty"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "[credentials.release]\n", "must not be empty"),
                org.junit.jupiter.params.provider.Arguments.of("[generated]\n", "must not be empty"));
    }

    @Test
    void distinguishesImpliedJavaParentFromAnAuthoredEmptyMainRequest() {
        validate("""
                [toolchain.java.test]
                version = 17
                """);

        assertFailureContains("""
                [toolchain.java]
                [toolchain.java.test]
                version = 17
                """, "direct main-toolchain field");
        assertFailureContains("""
                toolchain = { java = { test = { version = 17 } } }
                """, "direct main-toolchain field");

        validate("""
                [toolchain.java]
                policy = "prefer-managed"
                [toolchain.java.test]
                version = 17
                """);
    }

    @Test
    void distinguishesImpliedBomParentFromAnAuthoredBomWithoutMembers() {
        validate("[bom.versions]\n");
        validate("[bom.imports]\n");

        assertFailureContains("""
                [bom]
                [bom.versions]
                """, "must contain direct `members`");
        assertFailureContains("bom = { versions = {} }\n", "must contain direct `members`");

        validate("""
                [bom]
                members = true
                [bom.versions]
                """);
    }

    @Test
    void rejectsArrayTablesAndHeadersBeyondThreeSegments() {
        assertFailureContains("""
                [[tasks.release]]
                run = ["publish"]
                """, "Array tables are not part");
        assertFailureContains("""
                [generated.main.client.extra]
                value = true
                """, "three-segment table budget");
    }

    @Test
    void suggestionsAreSiblingScopedAndNeverOfferFieldsAsTables() {
        assertFailureContains("""
                [project]
                nmae = "demo"
                """, "Did you mean `project.name`?");
        assertFailureContains("""
                [toolchain.jvaa]
                version = 21
                """, "Did you mean `[toolchain.java]`?");

        ZoltConfigException tableFailure = failure("[workspace.nmae]\nvalue = true\n");
        assertTrue(tableFailure.getMessage().contains("Unknown manifest section"));
        assertFalse(tableFailure.getMessage().contains("[workspace.name]`?"));
    }

    @Test
    void reportsTheEarliestSourceFailureAcrossValidationPhases() {
        ZoltConfigException failure = failure("""
                [project]
                nmae = "demo"

                [[tasks.release]]
                run = ["publish"]
                """);

        assertTrue(failure.getMessage().contains("project.nmae"));
    }

    @Test
    void inlineTableFieldsCannotBeBuiltFromDottedChildAssignments() {
        assertFailureContains("""
                test.runtime.properties.answer = "yes"
                """, "must use an inline-table value");

        validate("""
                test.runtime.properties = { answer = "yes" }
                """);
        validate("""
                test = { runtime = { properties = { answer = "yes" } } }
                """);
    }

    private ValidatedManifestShape validate(String source) {
        return validator.validate(source, parser.parse(source));
    }

    private ZoltConfigException failure(String source) {
        return assertThrows(ZoltConfigException.class, () -> validate(source));
    }

    private void assertFailureContains(String source, String expected) {
        assertTrue(failure(source).getMessage().contains(expected));
    }
}
