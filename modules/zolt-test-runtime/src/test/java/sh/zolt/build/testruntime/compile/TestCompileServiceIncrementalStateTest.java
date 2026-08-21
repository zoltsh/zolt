package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavacException;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
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

final class TestCompileServiceIncrementalStateTest {
    private final TestCompileService testCompileService = new TestCompileService();

    @TempDir
    private Path projectDir;

    @Test
    void repeatedTestCompilationSkipsJavacWhenInputsAreCurrent() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source("src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");

        TestCompileResult first = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        TestCompileResult second = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertFalse(first.testCompilationSkipped());
        assertFalse(first.buildResult().mainCompilationSkipped());
        assertEquals("full", first.testCompilationMode());
        assertEquals("missing-state", first.testIncrementalFallbackReason());
        assertTrue(second.buildResult().mainCompilationSkipped());
        assertTrue(second.testCompilationSkipped());
        assertEquals(1, second.sourceCount());
        assertTrue(second.buildResult().mainFingerprintCheckNanos() > 0);
        assertTrue(first.buildResult().mainFingerprintWriteNanos() > 0);
        assertEquals(0L, second.buildResult().mainFingerprintWriteNanos());
        assertTrue(second.testFingerprintCheckNanos() > 0);
        assertTrue(first.testFingerprintWriteNanos() > 0);
        assertEquals(0L, second.testFingerprintWriteNanos());
        assertTrue(Files.exists(projectDir.resolve("target/classes/.zolt-build-main.fingerprint.state")));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/.zolt-build-test.fingerprint.state")));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainTest.class")));
    }

    @Test
    void fullTestCompileWritesIncrementalOwnershipState() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        Path testSource = source("src/test/java/com/example/MainTest.java", """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return new Helper().toString();
                    }

                    static final class Nested {
                    }
                }
                """);
        source("src/test/java/com/example/Helper.java", "package com.example; final class Helper {}\n");

        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        IncrementalCompileState state = new IncrementalCompileStateCodec()
                .read(projectDir.resolve("target/test-classes/.zolt-incremental-test.state"))
                .orElseThrow();
        IncrementalCompileState.SourceRecord testRecord = state.sources().stream()
                .filter(source -> source.path().equals(testSource.toAbsolutePath().normalize()))
                .findFirst()
                .orElseThrow();
        assertEquals("test", state.scope());
        assertTrue(state.fallbackReasons().isEmpty());
        assertTrue(testRecord.classOutputs().contains(projectDir
                .resolve("target/test-classes/com/example/MainTest.class")
                .toAbsolutePath()
                .normalize()));
        assertTrue(testRecord.classOutputs().contains(projectDir
                .resolve("target/test-classes/com/example/MainTest$Nested.class")
                .toAbsolutePath()
                .normalize()));
        assertTrue(state.reverseDependencies().containsKey("com.example.Helper"));
    }

    @Test
    void testSourceChangeInvalidatesTestCompileFingerprint() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        Path testSource = source(
                "src/test/java/com/example/MainTest.java",
                "package com.example; public final class MainTest { public String message() { return \"one\"; } }\n");
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(
                testSource,
                "package com.example; public final class MainTest { public String message() { return \"two\"; } }\n");

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertTrue(result.buildResult().mainCompilationSkipped());
        assertFalse(result.testCompilationSkipped());
    }

    @Test
    void failedIncrementalTestCompileInvalidatesStateForNextRun() throws IOException {
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
                    broken
                }
                """);

        assertThrows(
                JavacException.class,
                () -> testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache")));

        assertFalse(Files.exists(projectDir.resolve("target/test-classes/.zolt-incremental-test.state")));
        Files.writeString(testSource, """
                package com.example;

                public final class MainTest {
                    public String message() {
                        return "fixed";
                    }
                }
                """);

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("full", result.testCompilationMode());
        assertEquals("missing-state", result.testIncrementalFallbackReason());
    }

    @Test
    void missingExpectedTestClassPreventsTestCompileSkip() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source("src/test/java/com/example/MainTest.java", "package com.example; public final class MainTest {}\n");
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.delete(projectDir.resolve("target/test-classes/com/example/MainTest.class"));

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertTrue(result.buildResult().mainCompilationSkipped());
        assertFalse(result.testCompilationSkipped());
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/MainTest.class")));
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
