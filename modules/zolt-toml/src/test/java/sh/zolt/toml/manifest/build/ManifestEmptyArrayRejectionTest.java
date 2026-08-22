package sh.zolt.toml.manifest.build;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.ZoltConfigException;

/**
 * Design §5.5: omission is not an empty array. Where omitting a field activates a conventional
 * default, an explicitly authored empty array is rejected — v1 has no "disable the default"
 * spelling — and array entries are never blank, whitespace-only, or control-bearing.
 */
final class ManifestEmptyArrayRejectionTest {
    private static final String PROJECT = """
            [project]
            name = "demo"
            version = "1.0.0"
            group = "com.example"
            java = 21

            """;

    @ParameterizedTest(name = "{0}")
    @MethodSource("conventionalDefaultArrays")
    void explicitlyEmptyArrayIsRejectedWhereOmissionActivatesADefault(String field, String source) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decodeAuthoredManifest(PROJECT + source), field);

        assertTrue(failure.getMessage().contains(field), failure.getMessage());
        assertTrue(failure.getMessage().contains("must not be empty"), failure.getMessage());
    }

    private static List<Arguments> conventionalDefaultArrays() {
        return List.of(
                Arguments.of("build.sources", """
                        [build]
                        sources = []

                        [build.output]
                        root = "out"
                        """),
                Arguments.of("resources.main", """
                        [resources]
                        main = []
                        test = ["src/test/resources"]
                        """),
                Arguments.of("resources.test", """
                        [resources]
                        main = ["src/main/resources"]
                        test = []
                        """),
                Arguments.of("test.sources.java", """
                        [test.sources]
                        java = []
                        groovy = ["src/test/groovy"]
                        """),
                Arguments.of("test.sources.groovy", """
                        [test.sources]
                        java = ["src/test/java"]
                        groovy = []
                        """),
                Arguments.of("test.integration.sources", """
                        [test.integration]
                        sources = []
                        resources = ["src/integration-test/resources"]
                        """),
                Arguments.of("test.integration.resources", """
                        [test.integration]
                        sources = ["src/integration-test/java"]
                        resources = []
                        """));
    }

    @Test
    void explicitlyEmptyWorkspaceIncludeIsRejected() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeAuthoredManifest("""
                        [workspace]
                        name = "platform"

                        [workspace.members]
                        include = []
                        """));

        assertTrue(failure.getMessage().contains("include"), failure.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("blankEntryArrays")
    void blankArrayEntriesAreRejected(String kind, String source) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decodeAuthoredManifest(source), kind);

        assertTrue(failure.getMessage().contains("blank"), () -> kind + ": " + failure.getMessage());
    }

    private static List<Arguments> blankEntryArrays() {
        return List.of(
                Arguments.of("manifest relative path", PROJECT + """
                        [build]
                        sources = ["src/main/java", "  "]
                        """),
                Arguments.of("resource glob", PROJECT + """
                        [resources.filter]
                        include = ["  "]
                        """),
                Arguments.of("workspace member pattern", """
                        [workspace]
                        name = "platform"

                        [workspace.members]
                        include = ["  "]
                        """),
                Arguments.of("workspace member path", """
                        [workspace]
                        name = "platform"

                        [workspace.members]
                        include = ["apps/*"]
                        default = ["  "]
                        """));
    }
}
