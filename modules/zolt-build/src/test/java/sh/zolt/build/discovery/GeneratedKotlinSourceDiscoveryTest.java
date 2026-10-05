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
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProtobufGenerationSettings;

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
    void partitionsExecGeneratedKotlinRootsByLanguageInBothScopes() throws IOException {
        Path mainKotlin = source("target/generated/main/com/example/Generated.kt");
        Path testKotlin = source("target/generated/test/com/example/GeneratedTest.kt");
        source("target/generated/main/com/example/NotKotlin.java");
        source("target/generated/test/com/example/NotKotlinTest.java");
        source("schema/main.marker");
        source("schema/test.marker");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                BuildSettings.defaults().withGeneratedSources(
                        List.of(exec("main", "target/generated/main", "schema/main.marker",
                                ProducesLane.JAVA_SOURCES)),
                        List.of(exec("test", "target/generated/test", "schema/test.marker",
                                ProducesLane.TEST_SOURCES))));

        assertEquals(List.of(mainKotlin), result.kotlinMainSources());
        assertEquals(List.of(testKotlin), result.kotlinTestSources());
        assertEquals(List.of(), result.mainSources());
        assertEquals(List.of(), result.testSources());
    }

    @Test
    void partitionsOpenApiGeneratedKotlinRootsByLanguageInBothScopes() throws IOException {
        Path mainKotlin = source("target/generated/openapi/main/com/example/Client.kt");
        Path testKotlin = source("target/generated/openapi/test/com/example/Fixture.kt");
        source("schema/main.yaml");
        source("schema/test.yaml");

        SourceDiscoveryResult result = discoverer.discover(
                projectDir,
                BuildSettings.defaults().withGeneratedSources(
                        List.of(openApi("main", "target/generated/openapi/main", "schema/main.yaml")),
                        List.of(openApi("test", "target/generated/openapi/test", "schema/test.yaml"))));

        assertEquals(List.of(mainKotlin), result.kotlinMainSources());
        assertEquals(List.of(testKotlin), result.kotlinTestSources());
        assertEquals(List.of(), result.mainSources());
        assertEquals(List.of(), result.testSources());
    }

    @Test
    void rejectsKotlinForGeneratedKindsThatRemainJavaOnly() {
        GeneratedSourceStep protobuf = new GeneratedSourceStep(
                "protocol",
                GeneratedSourceKind.PROTOBUF,
                "kotlin",
                "generated/protocol",
                List.of("schema/protocol.proto"),
                true,
                false);

        SourceDiscoveryException failure = assertThrows(
                SourceDiscoveryException.class,
                () -> discoverer.discover(
                        projectDir,
                        BuildSettings.defaults().withGeneratedSources(List.of(protobuf), List.of())));

        assertTrue(failure.getMessage().contains("[generated.main.protocol]"), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("kind = \"openapi\""),
                failure.getMessage());
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

    private GeneratedSourceStep exec(
            String id,
            String output,
            String input,
            ProducesLane produces) {
        return new GeneratedSourceStep(
                id,
                GeneratedSourceKind.EXEC,
                "kotlin",
                output,
                List.of(input),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                new ExecGenerationSettings(
                        "generator",
                        ExecToolSettings.empty(),
                        List.of(),
                        produces,
                        Optional.empty(),
                        Map.of(),
                        "content"));
    }

    private GeneratedSourceStep openApi(String id, String output, String input) {
        return new GeneratedSourceStep(
                id,
                GeneratedSourceKind.OPENAPI,
                "kotlin",
                output,
                List.of(input),
                true,
                true);
    }

    private Path source(String relativePath) throws IOException {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "fixture\n");
        return source.normalize();
    }
}
