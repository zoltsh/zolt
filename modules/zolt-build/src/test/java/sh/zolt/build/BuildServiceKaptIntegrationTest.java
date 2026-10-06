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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real-compiler proof of Kotlin main annotation processing and generated-output reuse. */
final class BuildServiceKaptIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @TempDir
    private Path buildCacheHome;

    @Test
    void compilesKaptGeneratedJavaReferencedByKotlinAndJavaOffline() throws Exception {
        Path processor = AnnotationProcessorFixture.processorJar(
                projectDir.resolve("processor-work"));
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepareWithKaptProcessor(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"),
                        processor);
        source("src/main/kotlin/com/example/KotlinApi.kt", """
                package com.example

                object KotlinApi {
                    @JvmStatic
                    fun generated(): String = GeneratedMessage.value()
                }
                """);
        source("src/main/java/com/example/JavaApi.java", """
                package com.example;

                public final class JavaApi {
                    private JavaApi() {}

                    public static String generated() {
                        return KotlinApi.generated() + "-" + GeneratedMessage.value();
                    }
                }
                """);
        BuildService service = new BuildService().withBuildCache(BuildCacheService.create(
                new BuildCacheSettings(true, buildCacheHome, 0L),
                "kapt-main-integration"));

        BuildResultWithClasspaths first = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);

        Path generatedSource = projectDir.resolve(
                "target/generated/sources/annotations/com/example/GeneratedMessage.java");
        assertTrue(first.buildResult().resolveResult().isEmpty());
        assertEquals(2, first.buildResult().sourceCount());
        assertEquals("full", first.buildResult().mainCompilationMode());
        assertEquals("stored", first.buildResult().mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(generatedSource));
        assertTrue(Files.isRegularFile(classFile("GeneratedMessage.class")));
        assertTrue(Files.isRegularFile(classFile("KotlinApi.class")));
        assertTrue(Files.isRegularFile(classFile("JavaApi.class")));
        assertEquals("generated", invoke(artifacts.applicationClasspath(), "KotlinApi"));
        assertEquals("generated-generated", invoke(artifacts.applicationClasspath(), "JavaApi"));
        assertEquals(1, first.classpaths().processor().entries().size());
        assertFalse(first.classpaths().compile().entries().contains(
                first.classpaths().processor().entries().getFirst()));
        assertEquals(8, first.classpathPackages().stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_KOTLIN)
                .count());

        BuildResultWithClasspaths warm = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertTrue(warm.buildResult().mainCompilationSkipped());

        deleteRecursively(projectDir.resolve("target"));
        BuildResultWithClasspaths restored = service.buildWithClasspaths(
                projectDir,
                config(),
                cacheRoot,
                true);
        assertTrue(restored.buildResult().mainCompilationRestored());
        assertEquals("restored", restored.buildResult().mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(classFile("GeneratedMessage.class")));
        assertEquals("generated-generated", invoke(artifacts.applicationClasspath(), "JavaApi"));
    }

    private String invoke(List<Path> applicationClasspath, String simpleName) throws Exception {
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
            Class<?> api = Class.forName("com.example." + simpleName, true, loader);
            return (String) api.getMethod("generated").invoke(null);
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

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
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
                name = "kapt-main"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/java", "src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"

                [dependencies.processor]
                "com.example:greeting-processor" = "1.0.0"
                """);
    }
}
