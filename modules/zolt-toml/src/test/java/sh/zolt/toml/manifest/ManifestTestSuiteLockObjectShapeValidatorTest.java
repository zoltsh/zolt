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

final class ManifestTestSuiteLockObjectShapeValidatorTest {
    private static final String VALID =
            "{ class = \"repository\", resources = [\"database\"] }";

    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator();

    @Test
    void acceptsOneOrMultipleLocksIncludingExplicitEmptyResources() {
        validate(locks("[" + VALID + "]"));
        validate(locks("["
                + "{ class = \"database\", resources = [] }, "
                + "{ class = \"repository\", resources = [\"postgres\", \"redis\"] }"
                + "]"));
    }

    @Test
    void requiresTheClassAtTheExactArrayIndex() {
        assertFailureContains(
                locks("[" + VALID + ", { resources = [\"database\"] }]"),
                "Missing required inline-object field `test.suites.unit.locks[1].class`.");
    }

    @Test
    void requiresResourcesAtTheExactArrayIndex() {
        assertFailureContains(
                locks("[" + VALID + ", { class = \"database\" }]"),
                "Missing required inline-object field `test.suites.unit.locks[1].resources`.");
    }

    @Test
    void suggestsTheNearestCanonicalMemberAtTheExactArrayIndex() {
        assertFailureContains(
                locks("[" + VALID + ", { class = \"database\", resorces = [] }]"),
                "Unknown manifest field `test.suites.unit.locks[1].resorces`. "
                        + "Did you mean `test.suites.unit.locks[1].resources`?");
    }

    @ParameterizedTest
    @MethodSource("wrongMemberKinds")
    void rejectsWrongMemberKindsAtTheirExactArrayIndex(
            String value,
            String expected) {
        assertFailureContains(locks(value), expected);
    }

    static Stream<Arguments> wrongMemberKinds() {
        return Stream.of(
                Arguments.of(
                        "[{ class = 42 }]",
                        "Invalid value for `test.suites.unit.locks[0].class`: "
                                + "expected string but found integer."),
                Arguments.of(
                        "[" + VALID + ", { class = \"database\", resources = \"postgres\" }]",
                        "Invalid value for `test.suites.unit.locks[1].resources`: "
                                + "expected string array but found string."),
                Arguments.of(
                        "[" + VALID + ", { class = \"database\", resources = [1] }]",
                        "Invalid value for `test.suites.unit.locks[1].resources`: "
                                + "expected string array but found array."));
    }

    @Test
    void rejectsAHeterogeneousOuterArrayAtTheOwningField() {
        String message = failure(locks("[" + VALID + ", \"not-an-object\"]")).getMessage();

        assertTrue(message.contains(
                "Invalid value for `test.suites.unit.locks`: "
                        + "expected inline table array but found array."), message);
        assertFalse(message.contains("locks.["), message);
    }

    private static String locks(String value) {
        return "[test.suites.unit]\nlocks = " + value + "\n";
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
        assertFalse(message.contains("locks.["), message);
    }
}
