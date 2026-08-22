package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;

final class ManifestDependencyObjectShapeValidatorTest {
    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator();

    @Test
    void acceptsEveryDependencyConstraintAndDenyEntryBranch() {
        validate("""
                [dependencies]
                "org.example:shorthand" = "1"
                "org.example:fixed" = { version = "1", optional = true, publishOnly = false, classifier = "tests", type = "test-jar", exclude = ["org.bad:one"] }
                "org.example:alias" = { versionRef = "release" }
                "org.example:managed" = { managed = true }
                "org.example:workspace" = { workspace = true }

                [dependencies.constraints]
                "org.example:shorthand" = "1"
                "org.example:fixed" = { version = "1", reason = "minimum" }
                "org.example:alias" = { versionRef = "release" }

                [dependencies.policy]
                deny = [{ coordinate = "org.bad:one" }, { coordinate = "org.bad:two", reason = "blocked" }]
                """);
    }

    @ParameterizedTest
    @MethodSource("unknownMembers")
    void rejectsClosedObjectMembersWithExactSuggestions(
            String source,
            String unknownPath,
            String suggestion) {
        String message = failure(source).getMessage();

        assertTrue(message.contains("Unknown manifest field `" + unknownPath + "`"), message);
        assertTrue(message.contains("Did you mean `" + suggestion + "`?"), message);
    }

    static Stream<Arguments> unknownMembers() {
        return Stream.of(
                Arguments.of(
                        dependency("version = \"1\", versoin = \"2\""),
                        "dependencies.org.example:demo.versoin",
                        "dependencies.org.example:demo.version"),
                Arguments.of(
                        constraint("version = \"1\", reson = \"minimum\""),
                        "dependencies.constraints.org.example:demo.reson",
                        "dependencies.constraints.org.example:demo.reason"),
                Arguments.of(
                        deny("{ coordinate = \"org.bad:one\" }, "
                                + "{ coordiante = \"org.bad:two\" }"),
                        "dependencies.policy.deny[1].coordiante",
                        "dependencies.policy.deny[1].coordinate"));
    }

    @ParameterizedTest
    @MethodSource("invalidSelectorPresence")
    void enforcesRequiredAndExactOneSelectors(String source, String expected) {
        assertFailureContains(source, expected);
    }

    static Stream<Arguments> invalidSelectorPresence() {
        return Stream.of(
                Arguments.of(
                        dependency("optional = true"),
                        "must declare exactly one of `version` or `versionRef` or `managed` or `workspace`"),
                Arguments.of(
                        dependency("version = \"1\", managed = true"),
                        "must declare exactly one of `version` or `versionRef` or `managed` or `workspace`"),
                Arguments.of(
                        constraint("reason = \"missing selector\""),
                        "must declare exactly one of `version` or `versionRef`"),
                Arguments.of(
                        constraint("version = \"1\", versionRef = \"release\""),
                        "must declare exactly one of `version` or `versionRef`"),
                Arguments.of(
                        deny("{ coordinate = \"org.bad:one\" }, { reason = \"missing\" }"),
                        "Missing required inline-object field `dependencies.policy.deny[1].coordinate`"));
    }

    @ParameterizedTest
    @MethodSource("invalidMemberKinds")
    void rejectsEveryWrongMemberKind(String source, String path, String expectedKind) {
        assertFailureContains(
                source,
                "Invalid value for `" + path + "`: expected " + expectedKind);
    }

    static Stream<Arguments> invalidMemberKinds() {
        return Stream.of(
                dependencyKind("version", "1", "string"),
                dependencyKind("versionRef", "true", "string"),
                dependencyKind("managed", "\"true\"", "boolean"),
                dependencyKind("workspace", "\"true\"", "boolean"),
                dependencyKind("optional", "\"true\"", "boolean"),
                dependencyKind("publishOnly", "\"true\"", "boolean"),
                dependencyKind("classifier", "true", "string"),
                dependencyKind("type", "true", "string"),
                dependencyKind("exclude", "\"org.bad:one\"", "string array"),
                constraintKind("version", "1", "string"),
                constraintKind("versionRef", "true", "string"),
                constraintKind("reason", "true", "string"),
                denyKind("coordinate", "1", "string"),
                denyKind("reason", "true", "string"));
    }

    private static Arguments dependencyKind(String member, String value, String expectedKind) {
        boolean selector = switch (member) {
            case "version", "versionRef", "managed", "workspace" -> true;
            default -> false;
        };
        String body = (selector ? "" : "version = \"1\", ") + member + " = " + value;
        return Arguments.of(
                dependency(body),
                "dependencies.org.example:demo." + member,
                expectedKind);
    }

    private static Arguments constraintKind(String member, String value, String expectedKind) {
        String body = member.equals("reason")
                ? "version = \"1\", reason = " + value
                : member + " = " + value;
        return Arguments.of(
                constraint(body),
                "dependencies.constraints.org.example:demo." + member,
                expectedKind);
    }

    private static Arguments denyKind(String member, String value, String expectedKind) {
        String body = member.equals("reason")
                ? "coordinate = \"org.bad:two\", reason = " + value
                : "coordinate = " + value;
        return Arguments.of(
                deny("{ coordinate = \"org.bad:one\" }, { " + body + " }"),
                "dependencies.policy.deny[1]." + member,
                expectedKind);
    }

    private static String dependency(String body) {
        return "[dependencies]\n\"org.example:demo\" = { " + body + " }\n";
    }

    private static String constraint(String body) {
        return "[dependencies.constraints]\n\"org.example:demo\" = { " + body + " }\n";
    }

    private static String deny(String entries) {
        return "[dependencies.policy]\ndeny = [" + entries + "]\n";
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
