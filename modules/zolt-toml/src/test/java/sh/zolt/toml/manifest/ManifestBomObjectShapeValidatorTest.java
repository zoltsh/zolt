package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;

final class ManifestBomObjectShapeValidatorTest {
    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator();

    @Test
    void acceptsEveryBomVersionAndImportSelectorBranch() {
        validate("""
                [bom.versions]
                "org.example:shorthand" = "1"
                "org.example:fixed" = { version = "1", classifier = "tests", type = "test-jar" }
                "org.example:alias" = { versionRef = "release" }

                [bom.imports]
                "org.example:shorthand" = "1"
                "org.example:fixed" = { version = "1" }
                "org.example:alias" = { versionRef = "release" }
                """);
    }

    @ParameterizedTest
    @MethodSource("invalidSelectorPresence")
    void requiresExactlyOneVersionSelector(String source) {
        assertFailureContains(
                source,
                "must declare exactly one of `version` or `versionRef`");
    }

    private static Stream<String> invalidSelectorPresence() {
        return Stream.of(
                version("classifier = \"tests\""),
                version("version = \"1\", versionRef = \"release\""),
                imported("{ version = \"1\", versionRef = \"release\" }"));
    }

    @Test
    void leavesAnEmptyImportEntryToTheMutationShapeBoundary() {
        assertFailureContains(
                imported("{}"),
                "must not use an empty inline table `{}`; author its selector fields");
    }

    @ParameterizedTest
    @MethodSource("unknownMembers")
    void rejectsUnknownMembersAtTheirConcreteCoordinatePaths(
            String source,
            String path,
            String suggestion) {
        String message = failure(source).getMessage();

        assertTrue(message.contains("Unknown manifest field `" + path + "`"), message);
        if (!suggestion.isEmpty()) {
            assertTrue(message.contains("Did you mean `" + suggestion + "`?"), message);
        }
    }

    private static Stream<Arguments> unknownMembers() {
        return Stream.of(
                Arguments.of(
                        version("version = \"1\", clasifier = \"tests\""),
                        "bom.versions.org.example:demo.clasifier",
                        "bom.versions.org.example:demo.classifier"),
                Arguments.of(
                        imported("{ versionReff = \"release\" }"),
                        "bom.imports.org.example:demo.versionReff",
                        "bom.imports.org.example:demo.versionRef"),
                Arguments.of(
                        imported("{ version = \"1\", classifier = \"tests\" }"),
                        "bom.imports.org.example:demo.classifier",
                        ""),
                Arguments.of(
                        imported("{ version = \"1\", type = \"pom\" }"),
                        "bom.imports.org.example:demo.type",
                        ""));
    }

    @ParameterizedTest
    @MethodSource("wrongMemberKinds")
    void rejectsWrongMemberKinds(String source, String path) {
        assertFailureContains(
                source,
                "Invalid value for `" + path + "`: expected string");
    }

    private static Stream<Arguments> wrongMemberKinds() {
        return Stream.of(
                Arguments.of(version("version = 1"), "bom.versions.org.example:demo.version"),
                Arguments.of(
                        version("versionRef = true"),
                        "bom.versions.org.example:demo.versionRef"),
                Arguments.of(
                        version("version = \"1\", classifier = true"),
                        "bom.versions.org.example:demo.classifier"),
                Arguments.of(
                        version("version = \"1\", type = true"),
                        "bom.versions.org.example:demo.type"),
                Arguments.of(
                        imported("{ version = 1 }"),
                        "bom.imports.org.example:demo.version"),
                Arguments.of(
                        imported("{ versionRef = true }"),
                        "bom.imports.org.example:demo.versionRef"));
    }

    @Test
    void rejectsNonStringNonObjectEntriesAtTheOwningField() {
        String message = failure(versionValue("42")).getMessage();

        assertTrue(message.contains(
                "Invalid value for `bom.versions.org.example:demo`: "
                        + "expected string or inline table but found integer."), message);
        assertFalse(message.contains("demo.["), message);
    }

    private static String version(String body) {
        return versionValue("{ " + body + " }");
    }

    private static String versionValue(String value) {
        return "[bom.versions]\n\"org.example:demo\" = " + value + "\n";
    }

    private static String imported(String value) {
        return "[bom.imports]\n\"org.example:demo\" = " + value + "\n";
    }

    private void validate(String source) {
        validator.validate(parser.parse(source));
    }

    private ZoltConfigException failure(String source) {
        return assertThrows(ZoltConfigException.class, () -> validate(source));
    }

    private void assertFailureContains(String source, String expected) {
        String message = failure(source).getMessage();
        assertTrue(message.contains(expected), message);
    }
}
