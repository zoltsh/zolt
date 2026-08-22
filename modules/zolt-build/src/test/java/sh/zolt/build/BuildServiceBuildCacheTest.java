package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
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

/** End-to-end coverage of the build-output cache restoring compiled classes across a wiped target. */
final class BuildServiceBuildCacheTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheHome;

    @Test
    void restoresMainOutputAfterTargetWipeAndKeepsSkipGateCurrent() throws IOException {
        BuildService service = cacheEnabledService();
        writeProject("hello");

        BuildResult first = service.build(projectDir, config(), artifactCache());
        assertFalse(first.mainCompilationSkipped());
        assertFalse(first.mainCompilationRestored());
        assertEquals("stored", first.mainBuildCacheOutcome());
        byte[] compiled = Files.readAllBytes(mainClass());

        wipeTarget();
        BuildResult restored = service.build(projectDir, config(), artifactCache());

        assertTrue(restored.mainCompilationRestored(), "wiped target should restore from cache, not recompile");
        assertEquals("restored", restored.mainCompilationMode());
        assertEquals("restored", restored.mainBuildCacheOutcome());
        assertTrue(restored.mainRestoredClassCount() >= 1);
        assertArrayEquals(compiled, Files.readAllBytes(mainClass()), "restored bytes are identical to the compile");
        assertTrue(Files.exists(fingerprintFile()), "restore stamps the skip-gate fingerprint");
        assertFalse(Files.exists(incrementalStateFile()), "restored output carries no incremental state (v1 tradeoff)");

        BuildResult third = service.build(projectDir, config(), artifactCache());
        assertTrue(third.mainCompilationSkipped(), "the skip-gate is current immediately after a restore");
    }

    @Test
    void changedSourceMissesTheCacheAndRecompiles() throws IOException {
        BuildService service = cacheEnabledService();
        writeProject("hello");
        service.build(projectDir, config(), artifactCache());
        wipeTarget();
        assertTrue(service.build(projectDir, config(), artifactCache()).mainCompilationRestored());

        writeProject("goodbye");
        wipeTarget();
        BuildResult result = service.build(projectDir, config(), artifactCache());

        assertFalse(result.mainCompilationRestored(), "a source change yields a different key, so the cache misses");
        assertEquals("stored", result.mainBuildCacheOutcome());
    }

    @Test
    void editingAfterRestoreForcesOneFullRecompileThenIncrementalTakesOver() throws IOException {
        BuildService service = cacheEnabledService();
        writeProject("hello");
        service.build(projectDir, config(), artifactCache());
        wipeTarget();
        assertTrue(service.build(projectDir, config(), artifactCache()).mainCompilationRestored());

        // First edit after a restore: no incremental state exists, so the compiler falls back to a full
        // recompile that re-records the state (and re-stores the cache entry).
        writeProject("edited-one");
        BuildResult afterEdit = service.build(projectDir, config(), artifactCache());
        assertFalse(afterEdit.mainCompilationRestored());
        assertEquals("full", afterEdit.mainCompilationMode());
        assertEquals("missing-state", afterEdit.mainIncrementalFallbackReason());
        assertTrue(Files.exists(incrementalStateFile()), "the full recompile re-establishes incremental state");

        // Second edit: warm incremental state is present, so incremental compilation drives the build.
        writeProject("edited-two");
        BuildResult incremental = service.build(projectDir, config(), artifactCache());
        assertFalse(incremental.mainCompilationRestored());
        assertEquals("incremental", incremental.mainCompilationMode());
    }

    @Test
    void disabledCacheNeverRestores() throws IOException {
        BuildService service = new BuildService().withBuildCache(BuildCacheService.disabled());
        writeProject("hello");
        service.build(projectDir, config(), artifactCache());
        wipeTarget();

        BuildResult result = service.build(projectDir, config(), artifactCache());
        assertFalse(result.mainCompilationRestored());
        assertEquals("", result.mainBuildCacheOutcome());
    }

    private BuildService cacheEnabledService() {
        BuildCacheSettings settings = new BuildCacheSettings(true, cacheHome.resolve("build-cache"), 0L);
        return new BuildService().withBuildCache(BuildCacheService.create(settings, "test-version"));
    }

    private void writeProject(String message) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "%s";
                    }
                }
                """.formatted(message));
    }

    private Path mainClass() {
        return projectDir.resolve("target/classes/com/example/Main.class");
    }

    private Path fingerprintFile() {
        return projectDir.resolve("target/classes/.zolt-build-main.fingerprint");
    }

    private Path incrementalStateFile() {
        return projectDir.resolve("target/classes/.zolt-incremental-main.state");
    }

    private Path artifactCache() {
        return projectDir.resolve("cache");
    }

    private void wipeTarget() throws IOException {
        Path target = projectDir.resolve("target");
        if (!Files.exists(target)) {
            return;
        }
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

    private void source(String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
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
}
