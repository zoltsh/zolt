package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves the build cache stores and restores test-class output (and the main build it triggers). */
final class TestCompileServiceBuildCacheTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheHome;

    @Test
    void restoresMainAndTestOutputAfterTargetWipe() throws IOException {
        TestCompileService service = new TestCompileService()
                .withBuildCache(BuildCacheService.create(
                        new BuildCacheSettings(true, cacheHome.resolve("build-cache"), 0L), "test-version"));
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source("src/test/java/com/example/MainTest.java",
                "package com.example; public final class MainTest { public String go() { return new Main().toString(); } }\n");

        TestCompileResult first = service.compileTests(projectDir, config(), projectDir.resolve("cache"));
        assertFalse(first.testCompilationSkipped());
        assertFalse(first.buildResult().mainCompilationRestored());
        byte[] testClass = Files.readAllBytes(testClassFile());

        wipeTarget();
        TestCompileResult restored = service.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertTrue(restored.buildResult().mainCompilationRestored(), "main output restored from the cache");
        assertEquals("restored", restored.testCompilationMode(), "test output restored from the cache");
        assertArrayEquals(testClass, Files.readAllBytes(testClassFile()), "restored test bytes are identical");
        assertFalse(Files.exists(projectDir.resolve("target/test-classes/.zolt-incremental-test.state")),
                "restored test output carries no incremental state (v1 tradeoff)");
    }

    @Test
    void sameMajorCompilerChangeInvalidatesWarmTestOutputAndCacheRestore() throws IOException {
        BuildCacheService cache = BuildCacheService.create(
                new BuildCacheSettings(true, cacheHome.resolve("compiler-cache"), 0L), "test-version");
        TestCompileService firstCompiler = new TestCompileService(required -> compiler("same-major-a", required))
                .withBuildCache(cache);
        TestCompileService secondCompiler = new TestCompileService(required -> compiler("same-major-b", required))
                .withBuildCache(cache);
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source("src/test/java/com/example/MainTest.java",
                "package com.example; public final class MainTest { public String go() { return new Main().toString(); } }\n");

        assertFalse(firstCompiler.compileTests(projectDir, config(), projectDir.resolve("cache"))
                .testCompilationSkipped());
        assertTrue(firstCompiler.compileTests(projectDir, config(), projectDir.resolve("cache"))
                .testCompilationSkipped());

        TestCompileResult switched = secondCompiler.compileTests(projectDir, config(), projectDir.resolve("cache"));
        assertFalse(switched.testCompilationSkipped());
        assertFalse(switched.buildResult().mainCompilationRestored());
        assertEquals("full", switched.testCompilationMode());
        assertEquals("compiler-identity-changed", switched.testIncrementalFallbackReason());

        wipeTarget();
        TestCompileResult restored = firstCompiler.compileTests(projectDir, config(), projectDir.resolve("cache"));
        assertTrue(restored.buildResult().mainCompilationRestored());
        assertEquals("restored", restored.testCompilationMode());
    }

    private Path testClassFile() {
        return projectDir.resolve("target/test-classes/com/example/MainTest.class");
    }

    private void wipeTarget() throws IOException {
        Path target = projectDir.resolve("target");
        try (Stream<Path> paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
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

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", currentJavaMajorVersion(), Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static String currentJavaMajorVersion() {
        String[] parts = System.getProperty("java.version").split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }

    private static JdkStatus compiler(String identity, String requiredVersion) {
        Path javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
        String executableSuffix = System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
        return new JdkStatus(
                Optional.of(javaHome),
                Optional.of(javaHome.resolve("bin/java" + executableSuffix)),
                Optional.of(javaHome.resolve("bin/javac" + executableSuffix)),
                Optional.of(javaHome.resolve("bin/jar" + executableSuffix)),
                Optional.of(currentJavaMajorVersion()),
                Optional.of(identity),
                requiredVersion);
    }
}
