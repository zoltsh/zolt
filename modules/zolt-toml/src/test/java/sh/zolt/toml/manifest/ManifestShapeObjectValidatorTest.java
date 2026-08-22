package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;

final class ManifestShapeObjectValidatorTest {
    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator();

    @ParameterizedTest
    @MethodSource("validObjectAndUnionValues")
    void validatesOnlyTheInlineTableBranchOfRegisteredUnions(String source) {
        validate(source);
    }

    static Stream<String> validObjectAndUnionValues() {
        return Stream.of(
                "[project]\nlicense = \"MIT\"\n",
                "[project]\nlicense = { id = \"MIT\" }\n",
                "workspace = { project = { license = { name = \"Custom\" } } }\n",
                "[repositories]\ncentral = true\n",
                "[repositories]\ncentral = false\n",
                "[repositories]\ncentral = \"https://repo.example\"\n",
                "[repositories]\ncentral = { url = \"https://repo.example\", credentials = \"company\" }\n",
                "[platforms]\n\"org.example:demo\" = \"1.0.0\"\n",
                "[platforms]\n\"org.example:demo\" = { version = \"1.0.0\" }\n",
                "[platforms]\n\"org.example:demo\" = { versionRef = \"release\" }\n");
    }

    @ParameterizedTest
    @MethodSource("unknownMembers")
    void rejectsUnknownNestedMembersWithNearestCanonicalSuggestion(
            String source,
            String unknownPath,
            String suggestion) {
        ZoltConfigException failure = failure(source);
        assertTrue(failure.getMessage().contains("Unknown manifest field `" + unknownPath + "`"));
        assertTrue(failure.getMessage().contains("Did you mean `" + suggestion + "`?"));
    }

    static Stream<Arguments> unknownMembers() {
        return Stream.of(
                Arguments.of(
                        "[project]\nlicense = { nmae = \"MIT\" }\n",
                        "project.license.nmae",
                        "project.license.name"),
                Arguments.of(
                        "project = { license = { nmae = \"MIT\" } }\n",
                        "project.license.nmae",
                        "project.license.name"),
                Arguments.of(
                        "[repositories]\ncentral = { urll = \"https://repo.example\" }\n",
                        "repositories.central.urll",
                        "repositories.central.url"),
                Arguments.of(
                        "[platforms]\n\"org.example:demo\" = { versionReff = \"release\" }\n",
                        "platforms.org.example:demo.versionReff",
                        "platforms.org.example:demo.versionRef"));
    }

    @ParameterizedTest
    @MethodSource("wrongNestedKinds")
    void rejectsWrongNestedValueKinds(String source, String path, String actual) {
        assertFailureContains(source, "Invalid value for `" + path
                + "`: expected string but found " + actual + ".");
    }

    static Stream<Arguments> wrongNestedKinds() {
        return Stream.of(
                Arguments.of(
                        "[project]\nlicense = { id = 1 }\n",
                        "project.license.id",
                        "integer"),
                Arguments.of(
                        "[repositories]\ncentral = { url = true }\n",
                        "repositories.central.url",
                        "boolean"),
                Arguments.of(
                        "[platforms]\n\"org.example:demo\" = { version = false }\n",
                        "platforms.org.example:demo.version",
                        "boolean"));
    }

    @ParameterizedTest
    @MethodSource("missingPresence")
    void rejectsMissingRequiredAndInvalidPresenceGroups(String source, String expected) {
        assertFailureContains(source, expected);
    }

    static Stream<Arguments> missingPresence() {
        return Stream.of(
                Arguments.of(
                        "[project]\nlicense = { url = \"https://example.test\" }\n",
                        "must declare at least one of `id` or `name`"),
                Arguments.of(
                        "[repositories]\ncentral = { credentials = \"company\" }\n",
                        "Missing required inline-object field `repositories.central.url`"),
                Arguments.of(
                        "[platforms]\n\"org.example:demo\" = { version = \"1\", versionRef = \"release\" }\n",
                        "must declare exactly one of `version` or `versionRef`"));
    }

    private void validate(String source) {
        validator.validate(parser.parse(source));
    }

    private ZoltConfigException failure(String source) {
        return assertThrows(ZoltConfigException.class, () -> validate(source));
    }

    private void assertFailureContains(String source, String expected) {
        ZoltConfigException failure = failure(source);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
