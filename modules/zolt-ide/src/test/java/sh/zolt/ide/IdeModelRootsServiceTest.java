package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdeModelRootsServiceTest {
    private final IdeModelService service = new IdeModelService();

    @TempDir
    private Path tempDir;

    @Test
    void exportsMultipleJavaTestRootsDeterministically() throws IOException {
        Path projectDir = tempDir.resolve("multi-root-tests");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "multi-root-tests"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [test.sources]
                java = ["src/test/java", "src/integrationTest/java", "src/contractTest/java"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(List.of(
                new IdeModel.SourceRoot("main-java", "main", "java", root.resolve("src/main/java"), false),
                new IdeModel.SourceRoot(
                        "main-generated-java",
                        "main",
                        "java",
                        root.resolve("target/generated/sources/annotations"),
                        true),
                new IdeModel.SourceRoot("test-java-1", "test", "java", root.resolve("src/test/java"), false),
                new IdeModel.SourceRoot(
                        "test-java-2",
                        "test",
                        "java",
                        root.resolve("src/integrationTest/java"),
                        false),
                new IdeModel.SourceRoot(
                        "test-java-3",
                        "test",
                        "java",
                        root.resolve("src/contractTest/java"),
                        false),
                new IdeModel.SourceRoot(
                        "test-generated-java",
                        "test",
                        "java",
                        root.resolve("target/generated/test-sources/annotations"),
                        true),
                new IdeModel.SourceRoot(
                        "integration-test-java-1",
                        "integration-test",
                        "java",
                        root.resolve("src/integration-test/java"),
                        false)), model.sourceRoots());
    }

    @Test
    void exportsGroovyAndKotlinTestRootsDeterministically() throws IOException {
        Path projectDir = tempDir.resolve("groovy-tests");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "groovy-tests"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [test.sources]
                java = ["src/test/java"]
                groovy = ["src/test/groovy", "src/integrationTest/groovy"]
                kotlin = ["src/test/kotlin", "src/contractTest/kotlin"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertTrue(model.sourceRoots().contains(new IdeModel.SourceRoot(
                "test-groovy-1",
                "test",
                "groovy",
                root.resolve("src/test/groovy"),
                false)));
        assertTrue(model.sourceRoots().contains(new IdeModel.SourceRoot(
                "test-groovy-2",
                "test",
                "groovy",
                root.resolve("src/integrationTest/groovy"),
                false)));
        assertTrue(model.sourceRoots().contains(new IdeModel.SourceRoot(
                "test-kotlin-1",
                "test",
                "kotlin",
                root.resolve("src/test/kotlin"),
                false)));
        assertTrue(model.sourceRoots().contains(new IdeModel.SourceRoot(
                "test-kotlin-2",
                "test",
                "kotlin",
                root.resolve("src/contractTest/kotlin"),
                false)));
    }

    @Test
    void exportsConfiguredResourceRootsDeterministically() throws IOException {
        Path projectDir = tempDir.resolve("resource-roots");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "resource-roots"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [resources]
                main = ["src/main/resources", "target/generated/resources"]
                test = ["src/test/resources", "target/generated/test-resources"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(List.of(
                new IdeModel.ResourceRoot("main-resources", "main", root.resolve("src/main/resources")),
                new IdeModel.ResourceRoot("main-resources-2", "main", root.resolve("target/generated/resources")),
                new IdeModel.ResourceRoot("test-resources", "test", root.resolve("src/test/resources")),
                new IdeModel.ResourceRoot("test-resources-2", "test", root.resolve("target/generated/test-resources")),
                new IdeModel.ResourceRoot(
                        "integration-test-resources",
                        "integration-test",
                        root.resolve("src/integration-test/resources"))),
                model.resourceRoots());
    }

    @Test
    void exportsConfiguredMainSourceRootsInAuthoredOrder() throws IOException {
        Path projectDir = tempDir.resolve("multi-main-roots");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "multi-main-roots"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/java", "src/generated/java"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(List.of(
                new IdeModel.SourceRoot("main-java", "main", "java", root.resolve("src/main/java"), false),
                new IdeModel.SourceRoot("main-java-2", "main", "java", root.resolve("src/generated/java"), false),
                new IdeModel.SourceRoot(
                        "main-generated-java",
                        "main",
                        "java",
                        root.resolve("target/generated/sources/annotations"),
                        true),
                new IdeModel.SourceRoot("test-java-1", "test", "java", root.resolve("src/test/java"), false),
                new IdeModel.SourceRoot(
                        "test-generated-java",
                        "test",
                        "java",
                        root.resolve("target/generated/test-sources/annotations"),
                        true),
                new IdeModel.SourceRoot(
                        "integration-test-java-1",
                        "integration-test",
                        "java",
                        root.resolve("src/integration-test/java"),
                        false)), model.sourceRoots());
    }

    @Test
    void exportsExplicitKotlinMainRootAsKotlinInsteadOfJava() throws IOException {
        Path projectDir = tempDir.resolve("kotlin-main-root");
        Files.createDirectories(projectDir.resolve("src/main/kotlin"));
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "kotlin-main-root"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(List.of(new IdeModel.SourceRoot(
                "main-kotlin",
                "main",
                "kotlin",
                root.resolve("src/main/kotlin"),
                false)), authoredMainRoots(model));
    }

    @Test
    void exportsKotlinOnlyFilesFromAJavaNamedMainRootAsKotlin() throws IOException {
        Path projectDir = tempDir.resolve("kotlin-file-in-java-main-root");
        Path sourceRoot = projectDir.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Main.kt"), "package com.example\n");
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "kotlin-file-in-java-main-root"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        assertEquals(List.of(new IdeModel.SourceRoot(
                "main-kotlin",
                "main",
                "kotlin",
                projectDir.toAbsolutePath().normalize().resolve("src/main/java"),
                false)), authoredMainRoots(model));
    }

    @Test
    void exportsMixedJavaAndKotlinMainRootDeterministically() throws IOException {
        Path projectDir = tempDir.resolve("mixed-java-kotlin-main-root");
        Path sourceRoot = projectDir.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("JavaApi.java"), "package com.example; final class JavaApi {}\n");
        Files.writeString(sourceRoot.resolve("KotlinApi.kt"), "package com.example\ninternal object KotlinApi\n");
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "mixed-java-kotlin-main-root"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel first = service.export(projectDir, tempDir.resolve("cache"));
        IdeModel second = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize().resolve("src/main/java");
        List<IdeModel.SourceRoot> expected = List.of(
                new IdeModel.SourceRoot("main-java", "main", "java", root, false),
                new IdeModel.SourceRoot("main-kotlin", "main", "kotlin", root, false));
        assertEquals(expected, authoredMainRoots(first));
        assertEquals(expected, authoredMainRoots(second));
        String json = new IdeModelJsonWriter().write(first);
        assertTrue(json.contains("\"id\": \"main-java\""));
        assertTrue(json.contains("\"language\": \"java\""));
        assertTrue(json.contains("\"id\": \"main-kotlin\""));
        assertTrue(json.contains("\"language\": \"kotlin\""));
    }

    @Test
    void preservesConfiguredKotlinIdentityWhenRootContainsJava() throws IOException {
        Path projectDir = tempDir.resolve("java-file-in-kotlin-main-root");
        Path sourceRoot = projectDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("JavaApi.java"), "package com.example; final class JavaApi {}\n");
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "java-file-in-kotlin-main-root"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize().resolve("src/main/kotlin");
        assertEquals(List.of(
                new IdeModel.SourceRoot("main-java", "main", "java", root, false),
                new IdeModel.SourceRoot("main-kotlin", "main", "kotlin", root, false)),
                authoredMainRoots(model));
    }

    @Test
    void preservesJavaAndCustomKotlinMainRootsInAuthoredOrder() throws IOException {
        Path projectDir = tempDir.resolve("custom-kotlin-main-root");
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.createDirectories(projectDir.resolve("sources/platform/kotlin"));
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "custom-kotlin-main-root"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/java", "sources/platform/kotlin"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(List.of(
                new IdeModel.SourceRoot(
                        "main-java",
                        "main",
                        "java",
                        root.resolve("src/main/java"),
                        false),
                new IdeModel.SourceRoot(
                        "main-kotlin-2",
                        "main",
                        "kotlin",
                        root.resolve("sources/platform/kotlin"),
                        false)), authoredMainRoots(model));
    }

    @Test
    void doesNotAdvertiseKotlinTestSupportFromFilesInAJavaTestRoot() throws IOException {
        Path projectDir = tempDir.resolve("kotlin-file-in-java-test-root");
        Path testRoot = projectDir.resolve("src/test/java/com/example");
        Files.createDirectories(testRoot);
        Files.writeString(testRoot.resolve("ExampleTest.kt"), "package com.example\n");
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "kotlin-file-in-java-test-root"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        assertTrue(model.sourceRoots().stream()
                .filter(root -> "test".equals(root.kind()) && !root.generated())
                .allMatch(root -> "java".equals(root.language())));
    }

    @Test
    void exportsExplicitGroovyMainRootWithoutChangingSchemaOrJavaRows() throws IOException {
        Path projectDir = tempDir.resolve("explicit-groovy-main");
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.createDirectories(projectDir.resolve("src/main/groovy"));
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "explicit-groovy-main"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/java", "src/main/groovy"]
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel model = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize();
        assertEquals(1, model.schemaVersion());
        assertEquals(List.of(
                new IdeModel.SourceRoot("main-java", "main", "java", root.resolve("src/main/java"), false),
                new IdeModel.SourceRoot("main-java-2", "main", "java", root.resolve("src/main/groovy"), false),
                new IdeModel.SourceRoot("main-groovy-2", "main", "groovy", root.resolve("src/main/groovy"), false)),
                authoredMainRoots(model));
    }

    @Test
    void exportsMixedJavaAndGroovyMainRootDeterministically() throws IOException {
        Path projectDir = tempDir.resolve("mixed-main-root");
        Path sourceRoot = projectDir.resolve("src/main/java/com/example");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("JavaApi.java"), "package com.example; final class JavaApi {}\n");
        Files.writeString(sourceRoot.resolve("GroovyApi.groovy"), "package com.example\nfinal class GroovyApi {}\n");
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "mixed-main-root"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");

        IdeModel first = service.export(projectDir, tempDir.resolve("cache"));
        IdeModel second = service.export(projectDir, tempDir.resolve("cache"));

        Path root = projectDir.toAbsolutePath().normalize().resolve("src/main/java");
        List<IdeModel.SourceRoot> expected = List.of(
                new IdeModel.SourceRoot("main-java", "main", "java", root, false),
                new IdeModel.SourceRoot("main-groovy", "main", "groovy", root, false));
        assertEquals(expected, authoredMainRoots(first));
        assertEquals(expected, authoredMainRoots(second));
        String json = new IdeModelJsonWriter().write(first);
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"id\": \"main-groovy\""));
        assertTrue(json.contains("\"language\": \"groovy\""));
    }

    private static List<IdeModel.SourceRoot> authoredMainRoots(IdeModel model) {
        return model.sourceRoots().stream()
                .filter(root -> "main".equals(root.kind()) && !root.generated())
                .toList();
    }
}
