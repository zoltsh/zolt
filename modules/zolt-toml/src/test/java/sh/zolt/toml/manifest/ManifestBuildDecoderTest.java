package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.toml.ZoltConfigException;

final class ManifestBuildDecoderTest {
    @Test
    void preservesOmissionWithoutApplyingConventionalDefaults() {
        assertTrue(decode("").isEmpty());

        AuthoredBuild build = decode("""
                [build]
                sources = ["custom/java"]
                """).orElseThrow();
        assertEquals(List.of(path("custom/java")), build.sources());
        assertTrue(build.output().isEmpty());
        assertTrue(build.metadata().isEmpty());
    }

    @Test
    void decodesAllEightFieldsAndRetainsExplicitFalseMetadata() {
        AuthoredBuild build = decode("""
                [build]
                sources = ["src/zeta/java", "src/alpha/java"]

                [build.output]
                root = "target"
                main = "classes"
                test = "test-classes"
                integration = "integration-test-classes"

                [build.metadata]
                buildInfo = false
                git = true
                reproducible = false
                """).orElseThrow();

        assertEquals(
                List.of(path("src/zeta/java"), path("src/alpha/java")),
                build.sources());
        AuthoredBuild.Output output = build.output().orElseThrow();
        assertEquals(path("target"), output.root().orElseThrow());
        assertEquals(path("classes"), output.main().orElseThrow());
        assertEquals(path("test-classes"), output.test().orElseThrow());
        assertEquals(
                path("integration-test-classes"),
                output.integration().orElseThrow());
        AuthoredBuild.Metadata metadata = build.metadata().orElseThrow();
        assertFalse(metadata.buildInfo().orElseThrow());
        assertTrue(metadata.git().orElseThrow());
        assertFalse(metadata.reproducible().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> build.sources().clear());
    }

    @Test
    void childOnlyOutputAndMetadataFieldsCreateTheBuildDomain() {
        AuthoredBuild outputOnly = decode("""
                [build.output]
                test = "custom-test"
                """).orElseThrow();
        assertTrue(outputOnly.sources().isEmpty());
        assertEquals(
                path("custom-test"),
                outputOnly.output().orElseThrow().test().orElseThrow());
        assertTrue(outputOnly.metadata().isEmpty());

        AuthoredBuild metadataOnly = decode("""
                [build.metadata]
                git = false
                """).orElseThrow();
        assertTrue(metadataOnly.sources().isEmpty());
        assertTrue(metadataOnly.output().isEmpty());
        assertEquals(
                Optional.of(false),
                metadataOnly.metadata().orElseThrow().git());
    }

    @Test
    void rejectsAnEmptyOrDuplicateSourcesOnlyAggregateAtTheSourcesField() {
        assertFailure("""
                [build]
                sources = []
                """, "Invalid value for `build.sources`", "must not be empty");
        assertFailure("""
                [build]
                sources = ["src/main/java", "src/main/java"]
                """, "Invalid value for `build.sources`", "must not contain duplicate");
    }

    @Test
    void rejectsAnExplicitEmptySourceListEvenWhenAnotherBuildFieldHasAJob() {
        // §5.5: omission activates the conventional root; `[]` is not a way to disable it.
        assertFailure("""
                [build]
                sources = []

                [build.metadata]
                git = false
                """, "Invalid value for `build.sources`", "must not be empty");
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void anchorsInvalidPathsToTheirExactFields(String source, String path) {
        assertFailure(source, "Invalid value for `" + path + "`");
    }

    @ParameterizedTest
    @MethodSource("unsupportedSourceRoots")
    void rejectsSourceRootsForLanguagesZoltDoesNotBuild(String root, String language, String remedy) {
        // §10.1: an unsupported root must fail actionably rather than be silently ignored by javac.
        assertFailure(
                "[build]\nsources = [\"" + root + "\"]\n",
                "Invalid value for `build.sources`",
                "Unsupported " + language + " source root `" + root + "`",
                remedy);
    }

    private static List<Arguments> unsupportedSourceRoots() {
        String java = "Use Java source roots such as src/main/java";
        return List.of(
                Arguments.of("src/main/kotlin", "Kotlin", java),
                Arguments.of("kotlin", "Kotlin", java),
                Arguments.of("modules/core/src/main/KOTLIN", "Kotlin", java),
                Arguments.of("src/main/scala", "Scala", java),
                Arguments.of("src/android/java", "Android", "Use normal Java application source roots"),
                Arguments.of("android/src/main/java", "Android", "keep Android modules outside"));
    }

    @Test
    void keepsConventionalJavaSourceRootsThatMerelyMentionSupportedWords() {
        AuthoredBuild build = decode("""
                [build]
                sources = ["src/main/java", "src/kotlinx-compat/java"]
                """).orElseThrow();

        assertEquals(
                List.of(path("src/main/java"), path("src/kotlinx-compat/java")),
                build.sources());
    }

    private static List<Arguments> invalidPaths() {
        return List.of(
                Arguments.of("[build]\nsources = [\"/absolute\"]\n", "build.sources"),
                Arguments.of("[build.output]\nroot = \"/absolute\"\n", "build.output.root"),
                Arguments.of("[build.output]\nmain = \"../classes\"\n", "build.output.main"),
                Arguments.of("[build.output]\ntest = \".\"\n", "build.output.test"),
                Arguments.of(
                        "[build.output]\nintegration = \"nested//classes\"\n",
                        "build.output.integration"));
    }

    private static Optional<AuthoredBuild> decode(String source) {
        return new ManifestBuildDecoder().decode(
                ManifestSemanticTestSupport.index(source), ignored -> {});
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }
}
