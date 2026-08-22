package sh.zolt.build.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.NativeImageException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SpringBootAotNativeInputsTest {
    @TempDir
    private Path projectDir;

    @Test
    void collectsAotOutputEvidenceWithSortedGeneratedFiles() throws IOException {
        AotFiles files = writeCompleteAotOutput("target");
        SpringBootAotOutputEvidenceService service = new SpringBootAotOutputEvidenceService();

        SpringBootAotOutputEvidence evidence = service.collect(projectDir, "target");

        assertEquals(files.root(), evidence.outputRoot());
        assertIterableEquals(
                List.of(files.sources().resolve("com/example/Alpha.java"), files.sources().resolve("com/example/Zeta.java")),
                evidence.generatedSources());
        assertIterableEquals(
                List.of(files.classes().resolve("com/example/Alpha.class"), files.classes().resolve("com/example/Zeta.class")),
                evidence.generatedClasses());
        assertEquals(List.of(files.metadataRoot().resolve("reflect-config.json")), evidence.reflectionMetadata());
        assertEquals(List.of(files.metadataRoot().resolve("reachability-metadata.json")), evidence.reachabilityMetadata());
        assertTrue(evidence.fingerprint().startsWith("sha256:"));
        assertEquals(evidence.fingerprint(), service.collect(projectDir, "target").fingerprint());

        Files.writeString(files.sources().resolve("com/example/Alpha.java"), "class Alpha { int changed; }\n");

        assertNotEquals(evidence.fingerprint(), service.collect(projectDir, "target").fingerprint());
    }

    @Test
    void writesDeterministicEvidenceJsonWithRelativePaths() throws IOException {
        AotFiles files = writeCompleteAotOutput("custom-output");
        Path evidencePath = projectDir.resolve("target/native/spring-aot-evidence.json");

        Path written = new SpringBootAotOutputEvidenceService().write(projectDir, "custom-output", evidencePath);

        assertEquals(evidencePath, written);
        String json = Files.readString(evidencePath);
        assertTrue(json.contains("\"schema\": \"zolt.spring-aot-evidence.v1\""));
        assertTrue(json.contains("\"outputRoot\": \"custom-output/spring-aot/main\""));
        assertTrue(json.contains("\"path\": \"custom-output/spring-aot/main/sources\""));
        assertTrue(json.contains("\"exists\": true"));
        assertTrue(json.contains("\"path\": \"custom-output/spring-aot/main/resources/META-INF/native-image/com.example/demo/reflect-config.json\""));
        assertTrue(json.contains("\"path\": \"custom-output/spring-aot/main/resources/META-INF/native-image/com.example/demo/reachability-metadata.json\""));
        assertTrue(json.indexOf("Alpha.java") < json.indexOf("Zeta.java"));
        assertTrue(json.indexOf("Alpha.class") < json.indexOf("Zeta.class"));
        assertFalse(json.contains(projectDir.toString()), "evidence should use repo-relative paths");
        assertTrue(Files.exists(files.resources().resolve("application.properties")));
    }

    @Test
    void classpathEntriesReturnCompiledAotClassesAndResourcesWhenOutputIsFresh() throws IOException {
        AotFiles files = writeCompleteAotOutput("target");
        Path sourceInput = projectDir.resolve("src/main/java/com/example/App.java");
        Files.createDirectories(sourceInput.getParent());
        Files.writeString(sourceInput, "package com.example; class App {}\n");
        setModified(sourceInput, 1_000);
        setAotModified(files, 2_000);

        List<Path> entries = new SpringBootAotNativeInputs(projectDir, "target", List.of(sourceInput))
                .classpathEntries();

        assertEquals(List.of(files.classes(), files.resources()), entries);
    }

    @Test
    void classpathEntriesRejectMissingReflectionMetadataWithActionableMessage() throws IOException {
        AotFiles files = writeCompleteAotOutput("target");
        Files.delete(files.metadataRoot().resolve("reflect-config.json"));

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> new SpringBootAotNativeInputs(projectDir).classpathEntries());

        assertTrue(exception.getMessage().contains("missing Spring Boot AOT reflection metadata"));
        assertTrue(exception.getMessage().contains("[framework.spring-boot] native = true"));
    }

    @Test
    void classpathEntriesRejectStaleAotOutputWhenInputsAreNewer() throws IOException {
        AotFiles files = writeCompleteAotOutput("target");
        Path sourceInput = projectDir.resolve("src/main/java/com/example/App.java");
        Files.createDirectories(sourceInput.getParent());
        Files.writeString(sourceInput, "package com.example; class App {}\n");
        setAotModified(files, 1_000);
        setModified(sourceInput, 3_000);

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> new SpringBootAotNativeInputs(projectDir, "target", List.of(projectDir.resolve("src")))
                        .classpathEntries());

        assertTrue(exception.getMessage().contains("Spring Boot native AOT output is stale"));
        assertTrue(exception.getMessage().contains("Run `zolt build`"));
    }

    private AotFiles writeCompleteAotOutput(String outputRoot) throws IOException {
        Path root = projectDir.resolve(outputRoot).resolve("spring-aot/main");
        Path sources = root.resolve("sources");
        Path classes = root.resolve("classes");
        Path resources = root.resolve("resources");
        Path metadataRoot = resources.resolve("META-INF/native-image/com.example/demo");
        Files.createDirectories(sources.resolve("com/example"));
        Files.createDirectories(classes.resolve("com/example"));
        Files.createDirectories(metadataRoot);
        Files.writeString(sources.resolve("com/example/Zeta.java"), "class Zeta {}\n");
        Files.writeString(sources.resolve("com/example/Alpha.java"), "class Alpha {}\n");
        Files.writeString(classes.resolve("com/example/Zeta.class"), "zeta");
        Files.writeString(classes.resolve("com/example/Alpha.class"), "alpha");
        Files.writeString(resources.resolve("application.properties"), "spring.application.name=demo\n");
        Files.writeString(metadataRoot.resolve("reflect-config.json"), "[]\n");
        Files.writeString(metadataRoot.resolve("reachability-metadata.json"), "{\"resources\":[]}\n");
        return new AotFiles(root, sources, classes, resources, metadataRoot);
    }

    private static void setAotModified(AotFiles files, long millis) throws IOException {
        setModified(files.sources().resolve("com/example/Alpha.java"), millis);
        setModified(files.sources().resolve("com/example/Zeta.java"), millis);
        setModified(files.classes().resolve("com/example/Alpha.class"), millis);
        setModified(files.classes().resolve("com/example/Zeta.class"), millis);
        setModified(files.resources().resolve("application.properties"), millis);
        setModified(files.metadataRoot().resolve("reflect-config.json"), millis);
        setModified(files.metadataRoot().resolve("reachability-metadata.json"), millis);
    }

    private static void setModified(Path path, long millis) throws IOException {
        Files.setLastModifiedTime(path, FileTime.fromMillis(millis));
    }

    private record AotFiles(Path root, Path sources, Path classes, Path resources, Path metadataRoot) {
    }
}
