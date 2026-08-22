package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceDeclaredGeneratedSourceTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void declaredGeneratedSourceRootCompilesWithMainSources() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return GeneratedMessage.value();
                    }
                }
                """);
        source("src/main/openapi/api.yaml", "openapi: 3.1.0\n");
        source("target/generated/sources/openapi/com/example/GeneratedMessage.java", """
                package com.example;

                public final class GeneratedMessage {
                    public static String value() {
                        return "generated";
                    }
                }
                """);
        ProjectConfig config = config().withBuildSettings(config().build().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "openapi",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/sources/openapi",
                        List.of("src/main/openapi/api.yaml"),
                        true,
                        false)),
                List.of()));

        BuildResult result = buildService.build(projectDir, config, projectDir.resolve("cache"));

        assertEquals(2, result.sourceCount());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/GeneratedMessage.class")));
    }

    @Test
    void declaredGeneratedSourceInputChangeInvalidatesMainBuildFingerprint() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return GeneratedMessage.value();
                    }
                }
                """);
        Path input = source("src/main/openapi/api.yaml", "openapi: 3.1.0\n");
        source("target/generated/sources/openapi/com/example/GeneratedMessage.java", """
                package com.example;

                public final class GeneratedMessage {
                    public static String value() {
                        return "generated";
                    }
                }
                """);
        ProjectConfig config = config().withBuildSettings(config().build().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "openapi",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/sources/openapi",
                        List.of("src/main/openapi/api.yaml"),
                        true,
                        false)),
                List.of()));
        buildService.build(projectDir, config, projectDir.resolve("cache"));
        Files.writeString(input, "openapi: 3.1.0\ninfo:\n  title: Changed\n");

        BuildResult result = buildService.build(projectDir, config, projectDir.resolve("cache"));

        assertFalse(result.mainCompilationSkipped());
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "com.example",
                        currentJavaMajorVersion(),
                        Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private Path source(String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private void writeLockfile(String content) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
