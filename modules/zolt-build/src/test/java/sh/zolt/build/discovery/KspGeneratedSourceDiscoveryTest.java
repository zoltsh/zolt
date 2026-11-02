package sh.zolt.build.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.KspGenerationSettings;
import sh.zolt.project.KspProcessorSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProtobufGenerationSettings;

final class KspGeneratedSourceDiscoveryTest {
    @TempDir
    private Path projectDirectory;

    private final SourceDiscoverer discoverer = new SourceDiscoverer();

    @Test
    void excludesKspOutputsFromInputsThenPartitionsPublishedLanes() throws IOException {
        Path authoredJava = source("src/main/java/demo/App.java");
        Path authoredKotlin = source("src/main/java/demo/App.kt");
        Path generatedJava = source("target/generated/ksp/main/symbols/java/demo/Factory.java");
        Path generatedKotlin = source("target/generated/ksp/main/symbols/kotlin/demo/Factory.kt");
        source("target/generated/ksp/main/symbols/java/demo/Wrong.kt");
        source("target/generated/ksp/main/symbols/kotlin/demo/Wrong.java");
        BuildSettings settings = settings(step(true));

        SourceDiscoveryResult inputs = discoverer.discoverMainBeforeKsp(
                projectDirectory,
                settings);
        SourceDiscoveryResult published = discoverer.discoverMain(projectDirectory, settings);

        assertEquals(List.of(authoredJava), inputs.mainSources());
        assertEquals(List.of(authoredKotlin), inputs.kotlinMainSources());
        assertEquals(List.of(authoredJava, generatedJava), published.mainSources());
        assertEquals(List.of(authoredKotlin, generatedKotlin), published.kotlinMainSources());
    }

    @Test
    void requiredOutputMayBeAbsentBeforeKspButNotAfterward() throws IOException {
        Path authored = source("src/main/java/demo/App.kt");
        BuildSettings settings = settings(step(true));

        assertEquals(
                List.of(authored),
                discoverer.discoverMainBeforeKsp(projectDirectory, settings).kotlinMainSources());
        SourceDiscoveryException failure = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discoverMain(projectDirectory, settings));

        assertTrue(failure.getMessage().contains("KSP generated source root"));
        assertTrue(failure.getMessage().contains("Run KSP generation before source discovery"));
    }

    @Test
    void optionalMissingOutputContributesNoSources() throws IOException {
        Path authored = source("src/main/java/demo/App.kt");

        SourceDiscoveryResult result = discoverer.discoverMain(
                projectDirectory,
                settings(step(false)));

        assertEquals(List.of(authored), result.kotlinMainSources());
        assertTrue(result.mainSources().isEmpty());
    }

    @Test
    void excludesTestKspOutputsUntilTheyArePublished() throws IOException {
        Path authoredJava = source("src/test/java/demo/AppTest.java");
        Path authoredKotlin = source("src/test/kotlin/demo/AppTest.kt");
        Path generatedJava = source("target/generated/ksp/test/symbols/java/demo/Fixture.java");
        Path generatedKotlin = source("target/generated/ksp/test/symbols/kotlin/demo/Fixture.kt");
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
                List.of("src/test/kotlin"),
                defaults.resourceRoots(),
                defaults.testResourceRoots(),
                defaults.metadata()).withGeneratedSources(
                List.of(),
                List.of(step("test", true)));

        SourceDiscoveryResult inputs = discoverer.discoverTestBeforeKsp(
                projectDirectory,
                settings);
        SourceDiscoveryResult published = discoverer.discover(projectDirectory, settings);

        assertEquals(List.of(authoredJava), inputs.testSources());
        assertEquals(List.of(authoredKotlin), inputs.kotlinTestSources());
        assertEquals(List.of(authoredJava, generatedJava), published.testSources());
        assertEquals(List.of(authoredKotlin, generatedKotlin), published.kotlinTestSources());
    }

    private static BuildSettings settings(GeneratedSourceStep step) {
        return BuildSettings.defaults().withGeneratedSources(List.of(step), List.of());
    }

    private static GeneratedSourceStep step(boolean required) {
        return step("main", required);
    }

    private static GeneratedSourceStep step(String scope, boolean required) {
        return new GeneratedSourceStep(
                "symbols",
                GeneratedSourceKind.KSP,
                "kotlin",
                "target/generated/ksp/" + scope + "/symbols",
                List.of(),
                required,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                ExecGenerationSettings.empty(),
                new KspGenerationSettings(
                        "ksp",
                        Optional.of("2.2.0-2.0.2"),
                        Optional.empty(),
                        List.of(new KspProcessorSettings(
                                "com.example:processor", "1.0.0", Optional.empty())),
                        Map.of()));
    }

    private Path source(String relative) throws IOException {
        Path source = projectDirectory.resolve(relative);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "fixture\n");
        return source.normalize();
    }
}
