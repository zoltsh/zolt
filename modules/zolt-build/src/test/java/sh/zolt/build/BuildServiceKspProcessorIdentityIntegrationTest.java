package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Proves that checksum identity separates same-coordinate KSP processor revisions. */
final class BuildServiceKspProcessorIdentityIntegrationTest {
    @TempDir
    private Path projectDirectory;

    @TempDir
    private Path cacheRoot;

    @TempDir
    private Path buildCacheHome;

    @Test
    void changedProcessorBytesInvalidateNoOpAndOutputCache() throws Exception {
        Path firstProcessor = KspProcessorFixture.processorJar(
                projectDirectory.resolve("fixture/ksp-processor-v1.jar"),
                "processor-v1");
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KspCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDirectory.resolve("zolt.lock"),
                        firstProcessor);
        source("src/main/kotlin/com/example/Application.kt", """
                package com.example

                object Application {
                    @JvmStatic
                    fun value(): String = GeneratedKspMessage.value()
                }
                """);
        ProjectConfig config = config();
        BuildService service = new BuildService().withBuildCache(BuildCacheService.create(
                new BuildCacheSettings(true, buildCacheHome, 0L),
                "ksp-processor-identity"));

        BuildResultWithClasspaths first = build(service, config);
        assertEquals("stored", first.buildResult().mainBuildCacheOutcome());
        assertEquals("same-output", invoke(artifacts.applicationClasspath()));
        assertTrue(build(service, config).buildResult().mainCompilationSkipped());

        Path changedProcessor = KspProcessorFixture.processorJar(
                projectDirectory.resolve("fixture/ksp-processor-v2.jar"),
                "processor-v2");
        KspCompilerIntegrationArtifacts.replaceProcessor(
                cacheRoot,
                projectDirectory.resolve("zolt.lock"),
                changedProcessor);

        BuildResultWithClasspaths changed = build(service, config);
        assertFalse(changed.buildResult().mainCompilationSkipped());
        assertFalse(changed.buildResult().mainCompilationRestored());
        assertEquals("full", changed.buildResult().mainCompilationMode());
        assertEquals("", changed.buildResult().mainBuildCacheOutcome());
        assertEquals("same-output", invoke(artifacts.applicationClasspath()));

        deleteTree(projectDirectory.resolve("target"));
        BuildResultWithClasspaths changedCold = build(service, config);
        assertFalse(changedCold.buildResult().mainCompilationRestored());
        assertEquals("full", changedCold.buildResult().mainCompilationMode());
        assertEquals("stored", changedCold.buildResult().mainBuildCacheOutcome());

        deleteTree(projectDirectory.resolve("target"));
        BuildResultWithClasspaths changedRestore = build(service, config);
        assertTrue(changedRestore.buildResult().mainCompilationRestored());
        assertEquals("restored", changedRestore.buildResult().mainBuildCacheOutcome());
        assertEquals("same-output", invoke(artifacts.applicationClasspath()));

        KspCompilerIntegrationArtifacts.replaceProcessor(
                cacheRoot,
                projectDirectory.resolve("zolt.lock"),
                firstProcessor);
        deleteTree(projectDirectory.resolve("target"));
        BuildResultWithClasspaths originalRestore = build(service, config);
        assertTrue(originalRestore.buildResult().mainCompilationRestored());
        assertEquals("restored", originalRestore.buildResult().mainBuildCacheOutcome());
        assertEquals("same-output", invoke(artifacts.applicationClasspath()));
    }

    private BuildResultWithClasspaths build(BuildService service, ProjectConfig config) {
        return service.buildWithClasspaths(projectDirectory, config, cacheRoot, true);
    }

    private ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "ksp-processor-identity"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"

                [generated.tools.ksp]
                version = "2.2.0-2.0.2"
                coordinates = [
                    { coordinate = "com.example:ksp-fixture-processor", version = "1.0.0" },
                ]

                [generated.main.symbols]
                kind = "ksp"
                options = { "fixture.message" = "same-output" }
                """);
    }

    private void source(String relative, String content) throws IOException {
        Path path = projectDirectory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private String invoke(List<Path> applicationClasspath) throws Exception {
        URL[] urls = Stream.concat(
                        Stream.of(projectDirectory.resolve("target/classes")),
                        applicationClasspath.stream())
                .map(BuildServiceKspProcessorIdentityIntegrationTest::url)
                .toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            return (String) loader.loadClass("com.example.Application")
                    .getMethod("value")
                    .invoke(null);
        }
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (java.net.MalformedURLException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
