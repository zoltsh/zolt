package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceFingerprintInvalidationTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void zoltTomlChangeInvalidatesMainBuildFingerprint() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "name = \"demo\"\n");
        writeLockfile("version = 1\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(projectDir.resolve("zolt.toml"), "name = \"demo\"\nversion = \"0.1.1\"\n");

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertFalse(result.mainCompilationSkipped());
        assertEquals("fingerprint-mismatch:zoltToml", result.mainIncrementalFallbackReason());
    }

    @Test
    void lockfileChangeInvalidatesMainBuildFingerprint() throws IOException {
        writeLockfile("version = 1\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        writeLockfile("version = 1\n\n");

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertFalse(result.mainCompilationSkipped());
        assertEquals("fingerprint-mismatch:lockfile", result.mainIncrementalFallbackReason());
    }

    @Test
    void compilerSettingsChangeInvalidatesMainBuildFingerprint() throws IOException {
        writeLockfile("version = 1\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        ProjectConfig firstConfig = config();
        ProjectConfig secondConfig = withCompilerSettings(
                firstConfig,
                new CompilerSettings(
                        "target/generated/sources/annotations",
                        "target/generated/test-sources/annotations",
                        "17",
                        "UTF-8",
                        List.of("-Xlint:deprecation"),
                        List.of("-Xlint:unchecked")));
        buildService.build(projectDir, firstConfig, projectDir.resolve("cache"));

        BuildResult result = buildService.build(projectDir, secondConfig, projectDir.resolve("cache"));

        assertFalse(result.mainCompilationSkipped());
    }

    @Test
    void resourceChangeCopiesResourceWithoutWakingJavac() throws IOException {
        writeLockfile("version = 1\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        source("src/main/resources/application.properties", "message=hello\n");
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(projectDir.resolve("src/main/resources/application.properties"), "message=changed\n");

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertTrue(result.mainCompilationSkipped());
        assertEquals("message=changed\n", Files.readString(projectDir.resolve("target/classes/application.properties")));
    }

    @Test
    void missingExpectedClassFilePreventsMainBuildSkip() throws IOException {
        writeLockfile("version = 1\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "hello";
                    }
                }
                """);
        buildService.build(projectDir, config(), projectDir.resolve("cache"));
        Files.delete(projectDir.resolve("target/classes/com/example/Main.class"));

        BuildResult result = buildService.build(projectDir, config(), projectDir.resolve("cache"));

        assertFalse(result.mainCompilationSkipped());
        assertEquals(
                "missing-expected-class:target/classes/com/example/Main.class",
                result.mainIncrementalFallbackReason());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
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

    private static ProjectConfig withCompilerSettings(ProjectConfig config, CompilerSettings compilerSettings) {
        return ProjectConfigs.withDependencySections(
                config.project(),
                config.repositories(),
                config.platforms(),
                config.dependencies(),
                Set.of(),
                config.testDependencies(),
                Set.of(),
                config.annotationProcessors(),
                Set.of(),
                config.testAnnotationProcessors(),
                Set.of(),
                config.build(),
                NativeSettings.defaults(),
                compilerSettings);
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

    private void writeProcessorLockfile() throws IOException {
        writeLockfile("""
                version = 1

                [[package]]
                id = "com.example:processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "com/example/processor/1.0.0/processor-1.0.0.jar"
                dependencies = []
                """);
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
