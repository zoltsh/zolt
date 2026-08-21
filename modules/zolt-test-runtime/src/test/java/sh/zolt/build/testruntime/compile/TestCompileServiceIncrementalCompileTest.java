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

final class TestCompileServiceIncrementalCompileTest {
    private final TestCompileService testCompileService = new TestCompileService();

    @TempDir
    private Path projectDir;

    @Test
    void bodyOnlyTestSourceChangeUsesIncrementalTestCompilation() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        Path testSource = source("src/test/java/com/example/MainTest.java", """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return new Helper().toString() + " one";
                    }
                }
                """);
        source("src/test/java/com/example/Helper.java", "package com.example; final class Helper {}\n");
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(testSource, """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return new Helper().toString() + " two";
                    }
                }
                """);

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertTrue(result.buildResult().mainCompilationSkipped());
        assertFalse(result.testCompilationSkipped());
        assertEquals("incremental", result.testCompilationMode());
        assertEquals("", result.testIncrementalFallbackReason());
        assertEquals(1, result.sourceCount());
    }

    @Test
    void abiChangingTestSourceWithoutDependentsStaysIncrementalTestCompilation() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        Path testSource = source("src/test/java/com/example/MainTest.java", """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return "one";
                    }
                }
                """);
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(testSource, """
                package com.example;

                public final class MainTest {
                    public int message() {
                        return 2;
                    }
                }
                """);

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.testCompilationMode());
        assertEquals("", result.testIncrementalFallbackReason());
        assertEquals(1, result.sourceCount());
    }

    @Test
    void deletedSourceFallsBackToFullTestCompilationAndDeletesOwnedClass() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source("src/test/java/com/example/MainTest.java", """
                package com.example;

                public final class MainTest {
                }
                """);
        Path deletedSource = source("src/test/java/com/example/DeletedTest.java", """
                package com.example;

                public final class DeletedTest {
                }
                """);
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.delete(deletedSource);

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("full", result.testCompilationMode());
        assertEquals("source-deleted", result.testIncrementalFallbackReason());
        assertFalse(Files.exists(projectDir.resolve("target/test-classes/com/example/DeletedTest.class")));
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
