package sh.zolt.build.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;

final class GeneratedKotlinSourceDiscoveryTest {
    private final SourceDiscoverer discoverer = new SourceDiscoverer();

    @TempDir
    private Path projectDir;

    @Test
    void partitionsDeclaredGeneratedRootsByLanguageInBothScopes() throws IOException {
        Path mainKotlin = source("generated/main/com/example/Generated.kt");
        Path testKotlin = source("generated/test/com/example/GeneratedTest.kt");
        source("generated/main/com/example/NotKotlin.java");
        source("generated/test/com/example/NotKotlinTest.java");
        source("schema/main.marker");
        source("schema/test.marker");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                BuildSettings.defaults().withGeneratedSources(
                        List.of(declared("main", "kotlin", "generated/main", "schema/main.marker")),
                        List.of(declared("test", "kotlin", "generated/test", "schema/test.marker"))));

        assertEquals(List.of(mainKotlin), result.kotlinMainSources());
        assertEquals(List.of(testKotlin), result.kotlinTestSources());
        assertEquals(List.of(), result.mainSources());
        assertEquals(List.of(), result.testSources());
    }

    @Test
    void rejectsKotlinForGeneratedKindsThatRemainJavaOnly() {
        GeneratedSourceStep openApi = new GeneratedSourceStep(
                "api",
                GeneratedSourceKind.OPENAPI,
                "kotlin",
                "generated/api",
                List.of("schema/api.yaml"),
                true,
                false);

        SourceDiscoveryException failure = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(
                        projectDir,
                        BuildSettings.defaults().withGeneratedSources(List.of(openApi), List.of())));

        assertTrue(failure.getMessage().contains("[generated.main.api]"), failure.getMessage());
        assertTrue(failure.getMessage().contains("requires kind = \"declared-root\""), failure.getMessage());
    }

    @Test
    void rejectsUnknownGeneratedLanguagesWithTheBoundedContract() {
        GeneratedSourceStep scala = declared("scala", "scala", "generated/scala", "schema/scala.marker");

        SourceDiscoveryException failure = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(
                        projectDir,
                        BuildSettings.defaults().withGeneratedSources(List.of(scala), List.of())));

        assertTrue(failure.getMessage().contains("Supported generated source languages are java and kotlin"),
                failure.getMessage());
    }

    private GeneratedSourceStep declared(String id, String language, String output, String input) {
        return new GeneratedSourceStep(
                id,
                GeneratedSourceKind.DECLARED_ROOT,
                language,
                output,
                List.of(input),
                true,
                false);
    }

    private Path source(String relativePath) throws IOException {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "fixture\n");
        return source.normalize();
    }
}
