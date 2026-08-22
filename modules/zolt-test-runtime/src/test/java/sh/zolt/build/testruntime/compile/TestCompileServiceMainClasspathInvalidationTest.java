package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

final class TestCompileServiceMainClasspathInvalidationTest {
    private final TestCompileService testCompileService = new TestCompileService();

    @TempDir
    private Path projectDir;

    @Test
    void mainImplementationOnlyChangeDoesNotInvalidateTestCompileFingerprint() throws IOException {
        writeLockfile("version = 7\n");
        Path mainSource = source(
                "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static String message() { return \"one\"; } }\n");
        source("src/test/java/com/example/MainTest.java", """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return Main.message();
                    }
                }
                """);
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(
                mainSource,
                "package com.example; public final class Main { public static String message() { return \"two\"; } }\n");

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertFalse(result.buildResult().mainCompilationSkipped());
        assertTrue(result.testCompilationSkipped());
    }

    @Test
    void mainAbiChangeInvalidatesTestCompileFingerprint() throws IOException {
        writeLockfile("version = 7\n");
        Path mainSource = source(
                "src/main/java/com/example/Main.java",
                "package com.example; public final class Main { public static String message() { return \"one\"; } }\n");
        source("src/test/java/com/example/MainTest.java", """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return Main.message();
                    }
                }
                """);
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(
                mainSource,
                "package com.example; public final class Main { public static String message() { return \"two\"; } public static String extra() { return \"extra\"; } }\n");

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertFalse(result.buildResult().mainCompilationSkipped());
        assertFalse(result.testCompilationSkipped());
        assertEquals("full", result.testCompilationMode());
        assertEquals("compile-classpath-changed", result.testIncrementalFallbackReason());
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", currentJavaMajorVersion(), Optional.of("com.example.Main")),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
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
