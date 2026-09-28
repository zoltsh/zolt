package sh.zolt.build.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceDiscovererTest {
    private final SourceDiscoverer discoverer = new SourceDiscoverer();

    @TempDir
    private Path projectDir;

    @Test
    void findsMainAndTestJavaSources() throws IOException {
        Path main = source("src/main/java/com/example/Main.java");
        Path nested = source("src/main/java/com/example/internal/Helper.java");
        Path test = source("src/test/java/com/example/MainTest.java");
        source("src/main/java/com/example/readme.txt");

        SourceDiscoveryResult result = discoverer.discover(projectDir, BuildSettings.defaults());

        assertEquals(List.of(main, nested), result.mainSources());
        assertEquals(List.of(test), result.testSources());
    }

    @Test
    void findsMainJavaSourcesFromMultipleRootsDeterministically() throws IOException {
        Path generated = source("src/generated/java/com/example/Generated.java");
        Path main = source("src/main/java/com/example/Main.java");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                buildSettingsWithSourceRoots(List.of("src/generated/java", "src/main/java")));

        assertEquals(List.of(generated, main), result.mainSources());
    }

    @Test
    void findsMainGroovySourcesFromConfiguredRootsDeterministically() throws IOException {
        Path generated = source("src/generated/groovy/com/example/Generated.groovy");
        Path main = source("src/main/groovy/com/example/Main.groovy");
        Path java = source("src/main/java/com/example/JavaMain.java");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                buildSettingsWithSourceRoots(List.of(
                        "src/generated/groovy",
                        "src/main/groovy",
                        "src/main/java")));

        assertEquals(List.of(generated, main), result.groovyMainSources());
        assertEquals(List.of(java), result.mainSources());
        assertEquals(List.of(generated, main, java), result.allMainSources());
    }

    @Test
    void findsKotlinInAuthoredMainAndExplicitKotlinTestRootsDeterministically()
            throws IOException {
        Path main = source("src/main/java/com/example/Main.kt");
        Path mainFromSecondRoot = source("src/main/shared/com/example/Second.kt");
        Path firstTest = source("src/test/kotlin/com/example/MainTest.kt");
        Path secondTest = source("src/test-support/kotlin/com/example/MainSpec.kt");
        source("src/main/java/com/example/Script.kts");
        source("src/test/kotlin/com/example/TestScript.kts");
        BuildSettings defaults = BuildSettings.defaults();
        BuildSettings settings = new BuildSettings(
                defaults.source(),
                List.of("src/main/shared", "src/main/java"),
                defaults.test(),
                defaults.outputRoot(),
                defaults.output(),
                defaults.testOutput(),
                List.of("src/test/java"),
                List.of("src/test/groovy"),
                List.of("src/test-support/kotlin", "src/test/kotlin"),
                defaults.resourceRoots(),
                defaults.testResourceRoots(),
                defaults.metadata());

        SourceDiscoveryResult result = discoverer.discover(projectDir, settings);

        assertEquals(List.of(main, mainFromSecondRoot), result.kotlinMainSources());
        assertEquals(List.of(secondTest, firstTest), result.kotlinTestSources());
    }

    @Test
    void rejectsKotlinTestsOutsideExplicitKotlinRoots() throws IOException {
        source("src/test/java/com/example/MainTest.kt");

        SourceDiscoveryException failure = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(projectDir, BuildSettings.defaults()));

        assertTrue(failure.getMessage().contains("src/test/java/com/example/MainTest.kt"));
        assertTrue(failure.getMessage().contains("[test.sources].kotlin"));
    }

    @Test
    void rejectsKotlinTestsUnderGroovyRootsUnlessExplicitlyDeclared() throws IOException {
        source("src/test/groovy/com/example/MainSpec.kt");
        BuildSettings settings = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                List.of("src/test/groovy"));

        SourceDiscoveryException failure = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(projectDir, settings));

        assertTrue(failure.getMessage().contains("src/test/groovy/com/example/MainSpec.kt"));
        assertTrue(failure.getMessage().contains("[test.sources].kotlin"));
    }

    @Test
    void discoversJavaAndKotlinFromProjectedPolyglotIntegrationRoots() throws IOException {
        Path java = source("src/integration-test/java/com/example/JavaIT.java");
        Path kotlin = source("src/integration-test/java/com/example/KotlinIT.kt");
        source("src/integration-test/java/com/example/Script.kts");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                BuildSettings.defaults().asIntegrationTestBuild());

        assertEquals(List.of(java), result.testSources());
        assertEquals(List.of(kotlin), result.kotlinTestSources());
        assertEquals(List.of(java, kotlin), result.allTestSources());
    }

    @Test
    void admitsKotlinTestsWhenJavaAndKotlinRootsOverlapExplicitly() throws IOException {
        Path test = source("src/test/java/com/example/MainTest.kt");
        BuildSettings defaults = BuildSettings.defaults();
        BuildSettings settings = new BuildSettings(
                defaults.source(),
                defaults.sourceRoots(),
                defaults.test(),
                defaults.outputRoot(),
                defaults.output(),
                defaults.testOutput(),
                defaults.testSources(),
                defaults.groovyTestSources(),
                List.of("src/test/java"),
                defaults.resourceRoots(),
                defaults.testResourceRoots(),
                defaults.metadata());

        SourceDiscoveryResult result = discoverer.discover(projectDir, settings);

        assertEquals(List.of(test), result.kotlinTestSources());
    }

    @Test
    void findsJavaTestsFromMultipleRootsDeterministically() throws IOException {
        Path unit = source("src/test/java/com/example/MainTest.java");
        Path integration = source("src/integration-test/java/com/example/MainIT.java");
        Path fixture = source("src/fixtures/java/com/example/FixtureTest.java");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        "target/classes",
                        "target/test-classes",
                        List.of(
                                "src/integration-test/java",
                                "src/test/java",
                                "src/fixtures/java")));

        assertEquals(List.of(fixture, integration, unit), result.testSources());
    }

    @Test
    void findsGroovyTestsFromExplicitRootsDeterministically() throws IOException {
        Path unit = source("src/test/groovy/com/example/MainSpec.groovy");
        Path integration = source("src/integration-test/groovy/com/example/MainITSpec.groovy");
        source("src/test/groovy/com/example/NotGroovy.java");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        "target/classes",
                        "target/test-classes",
                        List.of("src/test/java"),
                        List.of("src/integration-test/groovy", "src/test/groovy"),
                        List.of("src/main/resources"),
                        List.of("src/test/resources"),
                        sh.zolt.project.BuildMetadataSettings.defaults()));

        assertEquals(List.of(integration, unit), result.groovyTestSources());
        assertEquals(List.of(), result.testSources());
        assertEquals(List.of(integration, unit), result.allTestSources());
    }

    @Test
    void deDuplicatesOverlappingTestRoots() throws IOException {
        Path test = source("src/test/java/com/example/MainTest.java");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        "target/classes",
                        "target/test-classes",
                        List.of("src/test", "src/test/java")));

        assertEquals(List.of(test), result.testSources());
    }

    @Test
    void ignoresTargetAndBuildOutputDirectories() throws IOException {
        Path app = source("src/main/java/com/example/App.java");
        source("target/classes/com/example/Generated.java");
        source("build/generated/com/example/Generated.java");
        source("target/test-classes/com/example/GeneratedTest.java");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                new BuildSettings(".", ".", "target/classes", "target/test-classes"));

        assertEquals(List.of(app), result.mainSources());
        assertEquals(List.of(app), result.testSources());
    }

    @Test
    void doesNotIgnoreJavaPackageNamedBuild() throws IOException {
        Path buildPackage = source("src/main/java/com/example/build/Tool.java");

        SourceDiscoveryResult result = discoverer.discover(projectDir, BuildSettings.defaults());

        assertEquals(List.of(buildPackage), result.mainSources());
    }

    @Test
    void missingSourceDirectoriesReturnEmptyLists() {
        SourceDiscoveryResult result = discoverer.discover(projectDir, BuildSettings.defaults());

        assertTrue(result.empty());
        assertTrue(result.mainSources().isEmpty());
        assertTrue(result.groovyMainSources().isEmpty());
        assertTrue(result.testSources().isEmpty());
    }

    @Test
    void includesDeclaredGeneratedSourceRoots() throws IOException {
        Path main = source("src/main/java/com/example/Main.java");
        Path generated = source("target/generated/sources/openapi/com/example/Generated.java");
        source("src/main/openapi/api.yaml");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                BuildSettings.defaults().withGeneratedSources(
                        List.of(new GeneratedSourceStep(
                                "openapi",
                                GeneratedSourceKind.DECLARED_ROOT,
                                "java",
                                "target/generated/sources/openapi",
                                List.of("src/main/openapi/api.yaml"),
                                true,
                                false)),
                        List.of()));

        assertEquals(List.of(main, generated), result.mainSources());
    }

    @Test
    void doesNotTreatGroovyFilesInJavaGeneratedRootsAsMainSources() throws IOException {
        Path main = source("src/main/groovy/com/example/Main.groovy");
        source("target/generated/sources/openapi/com/example/Unexpected.groovy");
        source("src/main/openapi/api.yaml");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                buildSettingsWithSourceRoots(List.of("src/main/groovy"))
                        .withGeneratedSources(
                                List.of(new GeneratedSourceStep(
                                        "openapi",
                                        GeneratedSourceKind.DECLARED_ROOT,
                                        "java",
                                        "target/generated/sources/openapi",
                                        List.of("src/main/openapi/api.yaml"),
                                        true,
                                        false)),
                                List.of()));

        assertEquals(List.of(main), result.groovyMainSources());
    }

    @Test
    void doesNotTreatKotlinFilesInGeneratedJavaRootsAsSources() throws IOException {
        source("target/generated/sources/openapi/com/example/Unexpected.kt");
        source("target/generated/test-sources/fixtures/com/example/UnexpectedTest.kt");
        source("src/main/openapi/api.yaml");
        source("src/test/fixtures/schema.json");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                BuildSettings.defaults().withGeneratedSources(
                        List.of(new GeneratedSourceStep(
                                "openapi",
                                GeneratedSourceKind.DECLARED_ROOT,
                                "java",
                                "target/generated/sources/openapi",
                                List.of("src/main/openapi/api.yaml"),
                                true,
                                false)),
                        List.of(new GeneratedSourceStep(
                                "fixtures",
                                GeneratedSourceKind.DECLARED_ROOT,
                                "java",
                                "target/generated/test-sources/fixtures",
                                List.of("src/test/fixtures/schema.json"),
                                true,
                                false))));

        assertEquals(List.of(), result.kotlinMainSources());
        assertEquals(List.of(), result.kotlinTestSources());
    }

    @Test
    void includesDeclaredGeneratedTestSourceRoots() throws IOException {
        Path generated = source("target/generated/test-sources/fixtures/com/example/GeneratedTest.java");
        source("src/test/fixtures/schema.json");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                BuildSettings.defaults().withGeneratedSources(
                        List.of(),
                        List.of(new GeneratedSourceStep(
                                "fixtures",
                                GeneratedSourceKind.DECLARED_ROOT,
                                "java",
                                "target/generated/test-sources/fixtures",
                                List.of("src/test/fixtures/schema.json"),
                                true,
                                false))));

        assertEquals(List.of(generated), result.testSources());
    }

    private Path source(String path) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "final class Example {}\n");
        return source.normalize();
    }

    private static BuildSettings buildSettingsWithSourceRoots(List<String> sourceRoots) {
        BuildSettings defaults = BuildSettings.defaults();
        return new BuildSettings(
                defaults.source(),
                sourceRoots,
                defaults.test(),
                defaults.outputRoot(),
                defaults.output(),
                defaults.testOutput(),
                defaults.testSources(),
                defaults.groovyTestSources(),
                defaults.resourceRoots(),
                defaults.testResourceRoots(),
                defaults.metadata());
    }
}
