package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceIncrementalMainAbiTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void publicAbiChangeRecompilesMainSourcesThatReferenceChangedClass() throws IOException {
        writeLockfile("version = 7\n");
        Path apiSource = source("src/main/java/com/example/api/Api.java", """
                package com.example.api;

                public final class Api {
                    public static String message() {
                        return "one";
                    }
                }
                """);
        source("src/main/java/com/example/app/UseApi.java", """
                package com.example.app;

                import com.example.api.Api;

                public final class UseApi {
                    public String message() {
                        return Api.message();
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(apiSource, """
                package com.example.api;

                public final class Api {
                    public static String message() {
                        return "two";
                    }

                    public static String extra() {
                        return "extra";
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertEquals(2, result.sourceCount());
    }

    @Test
    void privateOnlySourceChangeDoesNotRecompileMainDependents() throws IOException {
        writeLockfile("version = 7\n");
        Path apiSource = source("src/main/java/com/example/api/Api.java", """
                package com.example.api;

                public final class Api {
                    public static String message() {
                        return hidden();
                    }

                    private static String hidden() {
                        return "one";
                    }
                }
                """);
        source("src/main/java/com/example/app/UseApi.java", """
                package com.example.app;

                import com.example.api.Api;

                public final class UseApi {
                    public String message() {
                        return Api.message();
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(apiSource, """
                package com.example.api;

                public final class Api {
                    public static String message() {
                        return hidden();
                    }

                    private static String hidden() {
                        return "two";
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertEquals(1, result.sourceCount());
    }

    @Test
    void packagePrivateAbiChangeRecompilesSamePackageMainSources() throws IOException {
        writeLockfile("version = 7\n");
        Path apiSource = source("src/main/java/com/example/Api.java", """
                package com.example;

                public final class Api {
                    static String packageMessage() {
                        return "one";
                    }
                }
                """);
        source("src/main/java/com/example/Neighbor.java", """
                package com.example;

                public final class Neighbor {
                    public String message() {
                        return "neighbor";
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(apiSource, """
                package com.example;

                public final class Api {
                    static int packageMessage() {
                        return 2;
                    }
                }
                """);

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.mainCompilationMode());
        assertEquals("", result.mainIncrementalFallbackReason());
        assertEquals(2, result.sourceCount());
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
