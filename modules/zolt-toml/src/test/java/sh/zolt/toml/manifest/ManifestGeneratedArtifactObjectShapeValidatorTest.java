package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;

final class ManifestGeneratedArtifactObjectShapeValidatorTest {
    private static final String FIXED =
            "{ coordinate = \"org.example:fixed\", version = \"1.0.0\" }";
    private static final String REFERENCED =
            "{ coordinate = \"org.example:referenced\", versionRef = \"tool\" }";

    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator();

    @Test
    void acceptsFixedAndReferencedArtifactRequests() {
        validate(coordinates("[" + FIXED + ", " + REFERENCED + "]"));
    }

    @Test
    void requiresTheCoordinateAtTheExactArrayIndex() {
        assertFailureContains(
                coordinates("[" + FIXED + ", { version = \"2.0.0\" }]"),
                "Missing required inline-object field "
                        + "`generated.tools.codegen.coordinates[1].coordinate`.");
    }

    @ParameterizedTest
    @MethodSource("invalidVersionSelectors")
    void requiresExactlyOneVersionSelector(String value, int index) {
        assertFailureContains(
                coordinates(value),
                "Inline object `generated.tools.codegen.coordinates[" + index
                        + "]` must declare exactly one of `version` or `versionRef`.");
    }

    static Stream<Arguments> invalidVersionSelectors() {
        return Stream.of(
                Arguments.of("[{ coordinate = \"org.example:none\" }]", 0),
                Arguments.of(
                        "[" + FIXED
                                + ", { coordinate = \"org.example:both\", "
                                + "version = \"2.0.0\", versionRef = \"tool\" }]",
                        1));
    }

    @Test
    void suggestsTheNearestCanonicalMemberAtTheExactArrayIndex() {
        assertFailureContains(
                coordinates("[" + FIXED
                        + ", { coordiante = \"org.example:typo\", version = \"2.0.0\" }]"),
                "Unknown manifest field `generated.tools.codegen.coordinates[1].coordiante`. "
                        + "Did you mean `generated.tools.codegen.coordinates[1].coordinate`?");
    }

    @ParameterizedTest
    @MethodSource("wrongMemberKinds")
    void rejectsEveryWrongMemberKindAtItsExactArrayIndex(
            String value,
            int index,
            String member,
            String actualKind) {
        assertFailureContains(
                coordinates(value),
                "Invalid value for `generated.tools.codegen.coordinates[" + index + "]."
                        + member + "`: expected string but found " + actualKind + ".");
    }

    static Stream<Arguments> wrongMemberKinds() {
        return Stream.of(
                Arguments.of(
                        "[{ coordinate = 1, version = \"1.0.0\" }]",
                        0,
                        "coordinate",
                        "integer"),
                Arguments.of(
                        "[" + FIXED
                                + ", { coordinate = \"org.example:boolean\", version = true }]",
                        1,
                        "version",
                        "boolean"),
                Arguments.of(
                        "[" + FIXED + ", " + REFERENCED
                                + ", { coordinate = \"org.example:array\", versionRef = [] }]",
                        2,
                        "versionRef",
                        "array"));
    }

    private static String coordinates(String value) {
        return "[generated.tools.codegen]\ncoordinates = " + value + "\n";
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
