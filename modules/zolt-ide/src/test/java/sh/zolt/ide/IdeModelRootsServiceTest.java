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
                        true)), model.sourceRoots());
    }

    @Test
    void exportsGroovyTestRootsDeterministically() throws IOException {
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
                new IdeModel.ResourceRoot("test-resources-2", "test", root.resolve("target/generated/test-resources"))),
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
                        true)), model.sourceRoots());
    }

}
