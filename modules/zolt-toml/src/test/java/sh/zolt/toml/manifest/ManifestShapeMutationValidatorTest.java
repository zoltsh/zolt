package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;

final class ManifestShapeMutationValidatorTest {
    private final TomlSyntaxParser parser = new TomlSyntaxParser();
    private final ManifestShapeValidator validator = new ManifestShapeValidator();

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutableMaps")
    void acceptsDirectOneLineEntriesUnderEveryMutableHeader(MutableMap map) {
        validate(map.direct());
        validate("[" + map.path() + "]\n");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutableMaps")
    void rejectsDottedMutableEntries(MutableMap map) {
        assertMutableFailure(map, map.dotted());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutableMaps")
    void rejectsInlineParentMutableEntries(MutableMap map) {
        assertMutableFailure(map, map.inlineParent());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutableMaps")
    void rejectsLongFormDynamicSubtables(MutableMap map) {
        assertMutableFailure(map, map.longForm());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutableMaps")
    void rejectsMultilineMutableValues(MutableMap map) {
        assertMutableFailure(map, map.multiline());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tableValuedMutableMaps")
    void rejectsEmptySelectorsForEveryTableValuedMutableMap(MutableMap map) {
        assertFailureContains(map.emptySelector(), "must not use an empty inline table `{}`");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutableMaps")
    void rejectsLexicallyNoncanonicalMutableHeaders(MutableMap map) {
        assertFailureContains(map.noncanonicalHeader(), "must use the exact canonical header");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tableValuedMutableMaps")
    void showsTheExactCanonicalOneLineRewriteForLongFormEntries(MutableMap map) {
        String message = failure(map.longForm()).getMessage();

        assertTrue(message.contains("failure-safe manifest editor"), message);
        assertTrue(message.contains("as `" + map.renderedKey() + " = { version = \"1.0.0\" }`"), message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutableMaps")
    void showsTheExactCanonicalOneLineRewriteForDottedEntries(MutableMap map) {
        String message = failure(map.dotted()).getMessage();

        assertTrue(message.contains("as `" + map.renderedKey() + " = \"1.0.0\"`"), message);
    }

    @Test
    void oneLineStaticFieldsStillAllowDottedAndInlineParentToml() {
        validate("project.license = { id = \"MIT\" }\n");
        validate("project = { license = { id = \"MIT\" } }\n");

        assertFailureContains("[project]\nlicense = \"\"\"\nMIT\n\"\"\"\n",
                "one physical assignment line");
    }

    @Test
    void authoredMutableParentRequiresAHeaderEvenWhenItContainsOnlyStaticChildren() {
        assertFailureContains("""
                dependencies = { policy = { conflicts = "fail" } }
                """, "Entries in [dependencies]");

        validate("dependencies.policy.conflicts = \"fail\"\n");
        validate("""
                [dependencies]
                policy = { conflicts = "fail" }
                """);
    }

    static Stream<MutableMap> mutableMaps() {
        return Stream.of(
                new MutableMap("versions", "release"),
                new MutableMap("platforms", "org.example:library"),
                new MutableMap("dependencies", "org.example:library"),
                new MutableMap("dependencies.api", "org.example:library"),
                new MutableMap("dependencies.runtime", "org.example:library"),
                new MutableMap("dependencies.provided", "org.example:library"),
                new MutableMap("dependencies.dev", "org.example:library"),
                new MutableMap("dependencies.test", "org.example:library"),
                new MutableMap("dependencies.processor", "org.example:library"),
                new MutableMap("dependencies.test-processor", "org.example:library"),
                new MutableMap("dependencies.constraints", "org.example:library"),
                new MutableMap("bom.versions", "org.example:library"),
                new MutableMap("bom.imports", "org.example:library"));
    }

    static Stream<MutableMap> tableValuedMutableMaps() {
        return mutableMaps().filter(map -> !map.path().equals("versions"));
    }

    private void assertMutableFailure(MutableMap map, String source) {
        String message = failure(source).getMessage();
        assertTrue(message.contains("Entries in [" + map.path() + "]"), message);
        assertTrue(message.contains("explicit canonical table header"), message);
        assertTrue(message.contains("[" + map.path() + "]"), message);
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

    record MutableMap(String path, String key) {
        /** The key as canonical TOML: bare when the grammar allows it, quoted otherwise. */
        String renderedKey() {
            return key.chars().allMatch(character -> Character.isLetterOrDigit(character)
                    || character == '_' || character == '-')
                    ? key
                    : "\"" + key + "\"";
        }

        String direct() {
            return "[" + path + "]\n\"" + key + "\" = \"1.0.0\"\n";
        }

        String dotted() {
            return path + ".\"" + key + "\" = \"1.0.0\"\n";
        }

        String noncanonicalHeader() {
            String quoted = Arrays.stream(path.split("\\."))
                    .map(segment -> "\"" + segment + "\"")
                    .collect(java.util.stream.Collectors.joining(" . "));
            return "[ " + quoted + " ]\n\"" + key + "\" = \"1.0.0\"\n";
        }

        String inlineParent() {
            return path + " = { \"" + key + "\" = \"1.0.0\" }\n";
        }

        String longForm() {
            return "[" + path + ".\"" + key + "\"]\nversion = \"1.0.0\"\n";
        }

        String multiline() {
            return "[" + path + "]\n\"" + key + "\" = \"\"\"\n1.0.0\n\"\"\"\n";
        }

        String emptySelector() {
            return "[" + path + "]\n\"" + key + "\" = {}\n";
        }

        @Override
        public String toString() {
            return path;
        }
    }
}
