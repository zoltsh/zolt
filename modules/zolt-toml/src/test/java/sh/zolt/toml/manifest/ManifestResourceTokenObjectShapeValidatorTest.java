package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;

final class ManifestResourceTokenObjectShapeValidatorTest {
    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator();

    @Test
    void acceptsEachExactTokenSourceIncludingAnEmptyLiteralValue() {
        validate("""
                [resources.tokens]
                project-version = { project = "version" }
                release-token = { env = "RELEASE_TOKEN" }
                empty-literal = { value = "" }
                """);
    }

    @ParameterizedTest
    @MethodSource("invalidPresence")
    void requiresExactlyOneTokenSource(String body) {
        assertFailureContains(
                token("release-token", body),
                "must declare exactly one of `project` or `env` or `value`");
    }

    static Stream<String> invalidPresence() {
        return Stream.of(
                "",
                "project = \"version\", env = \"RELEASE_TOKEN\", value = \"release\"");
    }

    @Test
    void rejectsUnknownMembersWithTheNearestCanonicalSuggestion() {
        String message = failure(token("release-token", "vlaue = \"release\"")).getMessage();

        assertTrue(
                message.contains("Unknown manifest field `resources.tokens.release-token.vlaue`"),
                message);
        assertTrue(
                message.contains("Did you mean `resources.tokens.release-token.value`?"),
                message);
    }

    @ParameterizedTest
    @MethodSource("wrongMemberKinds")
    void rejectsEveryWrongMemberKind(String member, String value, String actualKind) {
        assertFailureContains(
                token("release-token", member + " = " + value),
                "Invalid value for `resources.tokens.release-token." + member
                        + "`: expected string but found " + actualKind + ".");
    }

    static Stream<Arguments> wrongMemberKinds() {
        return Stream.of(
                Arguments.of("project", "1", "integer"),
                Arguments.of("env", "true", "boolean"),
                Arguments.of("value", "[]", "array"));
    }

    @Test
    void enforcesTheLocalIdGrammarAtTheDynamicTokenKey() {
        assertFailureContains(
                token("Bad_Id", "value = \"release\""),
                "Invalid dynamic key `Bad_Id` at `resources.tokens.Bad_Id`");
    }

    private static String token(String id, String body) {
        return "[resources.tokens]\n" + id + " = { " + body + " }\n";
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
