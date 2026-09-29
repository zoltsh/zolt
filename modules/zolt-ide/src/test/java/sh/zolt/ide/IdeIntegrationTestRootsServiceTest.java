package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdeIntegrationTestRootsServiceTest {
    private final IdeModelService service = new IdeModelService();

    @TempDir
    private Path tempDir;

    @Test
    void exportsJavaKotlinAndMixedIntegrationTestRootsInAuthoredOrder() throws IOException {
        Path projectDir = tempDir.resolve("integration-test-roots");
        Path mixedRoot = projectDir.resolve("src/it/mixed/com/example");
        Files.createDirectories(mixedRoot);
        Files.writeString(mixedRoot.resolve("JavaIT.java"), "package com.example; final class JavaIT {}\n");
        Files.writeString(mixedRoot.resolve("KotlinIT.kt"), "package com.example\ninternal object KotlinIT\n");
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "integration-test-roots"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [test.integration]
                sources = ["src/it/java", "src/it/kotlin", "src/it/mixed"]
                resources = ["src/it/resources", "src/contract/resources"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel first = service.export(projectDir, tempDir.resolve("cache"));
        IdeModel second = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        List<IdeModel.SourceRoot> expectedSources = List.of(
                new IdeModel.SourceRoot(
                        "integration-test-java-1", "integration-test", "java", root.resolve("src/it/java"), false),
                new IdeModel.SourceRoot(
                        "integration-test-kotlin-2", "integration-test", "kotlin", root.resolve("src/it/kotlin"), false),
                new IdeModel.SourceRoot(
                        "integration-test-java-3", "integration-test", "java", root.resolve("src/it/mixed"), false),
                new IdeModel.SourceRoot(
                        "integration-test-kotlin-3", "integration-test", "kotlin", root.resolve("src/it/mixed"), false));
        assertEquals(expectedSources, integrationTestSourceRoots(first));
        assertEquals(expectedSources, integrationTestSourceRoots(second));
        assertEquals(List.of(
                new IdeModel.ResourceRoot(
                        "integration-test-resources", "integration-test", root.resolve("src/it/resources")),
                new IdeModel.ResourceRoot(
                        "integration-test-resources-2", "integration-test", root.resolve("src/contract/resources"))),
                integrationTestResourceRoots(first));
        assertEquals(1, first.schemaVersion());
        String json = new IdeModelJsonWriter().write(first);
        assertTrue(json.contains("\"id\": \"integration-test-kotlin-2\""));
        assertTrue(json.contains("\"kind\": \"integration-test\""));
    }

    private static List<IdeModel.SourceRoot> integrationTestSourceRoots(IdeModel model) {
        return model.sourceRoots().stream()
                .filter(root -> "integration-test".equals(root.kind()))
                .toList();
    }

    private static List<IdeModel.ResourceRoot> integrationTestResourceRoots(IdeModel model) {
        return model.resourceRoots().stream()
                .filter(root -> "integration-test".equals(root.kind()))
                .toList();
    }
}
