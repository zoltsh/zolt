package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** End-to-end proof that the production build path runs an isolated real Kotlin compiler. */
final class BuildServiceKotlinMainIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @TempDir
    private Path buildCacheHome;

    @Test
    void compilesOfflineWithAnIsolatedToolchainAndSafelyReusesOutput() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts = KotlinCompilerIntegrationArtifacts.prepare(
                cacheRoot,
                projectDir.resolve("zolt.lock"));
        Path kotlinSource = source("src/main/kotlin/com/example/KotlinApi.kt", """
                package com.example

                object KotlinApi {
                    @JvmStatic
                    fun message(): String = listOf("real", "kotlin").joinToString("-")
                }
                """);
        Path obsoleteSource = source("src/main/kotlin/com/example/Obsolete.kt", """
                package com.example

                class Obsolete
                """);
        BuildService service = new BuildService().withBuildCache(BuildCacheService.create(
                new BuildCacheSettings(true, buildCacheHome, 0L),
                "kotlin-main-integration"));

        BuildResultWithClasspaths first = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);

        assertTrue(first.buildResult().resolveResult().isEmpty());
        assertEquals(2, first.buildResult().sourceCount());
        assertEquals("full", first.buildResult().mainCompilationMode());
        assertEquals("kotlin-main-sources", first.buildResult().mainIncrementalFallbackReason());
        assertEquals("stored", first.buildResult().mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(classFile("KotlinApi.class")));
        assertTrue(Files.isRegularFile(classFile("Obsolete.class")));
        assertTrue(hasKotlinModuleMetadata());
        assertEquals("real-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));
        assertIsolatedCompilerClasspath(first, artifacts);

        BuildResultWithClasspaths warm = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertTrue(warm.buildResult().resolveResult().isEmpty());
        assertTrue(warm.buildResult().mainCompilationSkipped());
        assertEquals(2, warm.buildResult().sourceCount());

        wipeTarget();
        BuildResultWithClasspaths restored = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertTrue(restored.buildResult().mainCompilationRestored());
        assertEquals("restored", restored.buildResult().mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(classFile("KotlinApi.class")));
        assertTrue(Files.isRegularFile(classFile("Obsolete.class")));
        assertTrue(hasKotlinModuleMetadata());
        assertEquals("real-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));

        Files.delete(obsoleteSource);
        BuildResultWithClasspaths rebuilt = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertFalse(rebuilt.buildResult().mainCompilationSkipped());
        assertFalse(rebuilt.buildResult().mainCompilationRestored());
        assertEquals("full", rebuilt.buildResult().mainCompilationMode());
        assertEquals("kotlin-main-sources", rebuilt.buildResult().mainIncrementalFallbackReason());
        assertEquals(1, rebuilt.buildResult().sourceCount());
        assertFalse(Files.exists(classFile("Obsolete.class")));
        assertTrue(Files.isRegularFile(kotlinSource));
        assertEquals("real-kotlin", invokeKotlinApi(artifacts.applicationClasspath()));
    }

    private void assertIsolatedCompilerClasspath(
            BuildResultWithClasspaths result,
            KotlinCompilerIntegrationArtifacts.Prepared artifacts) {
        List<Path> compileEntries = result.classpaths().compile().entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        assertEquals(new HashSet<>(artifacts.applicationClasspath()), new HashSet<>(compileEntries));
        assertEquals(2, compileEntries.size());
        assertEquals(7, result.classpathPackages().stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_KOTLIN)
                .count());
        Set<Path> applicationEntries = Set.copyOf(compileEntries);
        List<Path> toolOnlyEntries = result.classpathPackages().stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_KOTLIN)
                .map(ResolvedClasspathPackage::resolvedPackage)
                .map(resolved -> resolved.jarPath().toAbsolutePath().normalize())
                .filter(path -> !applicationEntries.contains(path))
                .toList();
        assertEquals(5, toolOnlyEntries.size());
        assertTrue(artifacts.compilerClasspath().containsAll(toolOnlyEntries));
        assertTrue(compileEntries.stream().noneMatch(toolOnlyEntries::contains));
    }

    private String invokeKotlinApi(List<Path> applicationClasspath) throws Exception {
        URL[] urls = Stream.concat(
                        Stream.of(projectDir.resolve("target/classes")),
                        applicationClasspath.stream())
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (java.net.MalformedURLException exception) {
                        throw new IllegalArgumentException(exception);
                    }
                })
                .toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> api = Class.forName("com.example.KotlinApi", true, loader);
            return (String) api.getMethod("message").invoke(null);
        }
    }

    private boolean hasKotlinModuleMetadata() throws IOException {
        Path metadata = projectDir.resolve("target/classes/META-INF");
        if (!Files.isDirectory(metadata)) {
            return false;
        }
        try (Stream<Path> paths = Files.list(metadata)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".kotlin_module"));
        }
    }

    private Path classFile(String name) {
        return projectDir.resolve("target/classes/com/example").resolve(name);
    }

    private Path source(String relativePath, String content) throws IOException {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
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

    private static ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-main"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """);
    }
}
