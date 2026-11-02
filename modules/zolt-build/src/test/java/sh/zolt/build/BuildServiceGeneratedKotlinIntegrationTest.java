package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler and cache lifecycle for a declared generated Kotlin main root. */
final class BuildServiceGeneratedKotlinIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @TempDir
    private Path buildCacheHome;

    @Test
    void compilesCachesInvalidatesAndProtectsGeneratedKotlin() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts = KotlinCompilerIntegrationArtifacts.prepare(
                cacheRoot, projectDir.resolve("zolt.lock"));
        source("src/main/java/com/example/JavaApi.java", """
                package com.example;

                public final class JavaApi {
                    public static String value() {
                        return "java";
                    }

                    public static String callGenerated() {
                        return GeneratedKotlin.message();
                    }
                }
                """);
        source("schema/generated.marker", "declared generated Kotlin\n");
        Path generatedSource = generatedSource("v1");
        BuildService service = new BuildService().withBuildCache(BuildCacheService.create(
                new BuildCacheSettings(true, buildCacheHome, 0L),
                "generated-kotlin-main-integration"));

        BuildResult first = service.buildWithClasspaths(projectDir, config(), cacheRoot, true)
                .buildResult();

        assertEquals(2, first.sourceCount());
        assertEquals("full", first.mainCompilationMode());
        assertEquals("kotlin-main-sources", first.mainIncrementalFallbackReason());
        assertEquals("stored", first.mainBuildCacheOutcome());
        assertEquals("java-v1", invoke("com.example.GeneratedKotlin", "message", artifacts));
        assertEquals("java-v1", invoke("com.example.JavaApi", "callGenerated", artifacts));

        BuildResult warm = service.buildWithClasspaths(projectDir, config(), cacheRoot, true)
                .buildResult();
        assertTrue(warm.mainCompilationSkipped());

        deleteTree(projectDir.resolve("target"));
        BuildResult restored = service.buildWithClasspaths(projectDir, config(), cacheRoot, true)
                .buildResult();
        assertTrue(restored.mainCompilationRestored());
        assertTrue(Files.isRegularFile(generatedSource));
        assertEquals("java-v1", invoke("com.example.JavaApi", "callGenerated", artifacts));

        generatedSource("v2");
        BuildResult changed = service.buildWithClasspaths(projectDir, config(), cacheRoot, true)
                .buildResult();
        assertFalse(changed.mainCompilationSkipped());
        assertFalse(changed.mainCompilationRestored());
        assertEquals("full", changed.mainCompilationMode());
        assertEquals("java-v2", invoke("com.example.JavaApi", "callGenerated", artifacts));

        deleteTree(projectDir.resolve("generated/main"));
        SourceDiscoveryException missing = assertThrows(
                SourceDiscoveryException.class,
                () -> service.buildWithClasspaths(projectDir, config(), cacheRoot, true));
        assertTrue(missing.getMessage().contains("Generated source root `generated/main` is missing"));
        assertTrue(Files.isRegularFile(classFile("GeneratedKotlin.class")));
        assertTrue(Files.isRegularFile(classFile("JavaApi.class")));
    }

    private Path generatedSource(String revision) throws IOException {
        return source("generated/main/com/example/GeneratedKotlin.kt", """
                package com.example

                object GeneratedKotlin {
                    @JvmStatic
                    fun message(): String = JavaApi.value() + "-%s"
                }
                """.formatted(revision));
    }

    private Object invoke(
            String className,
            String methodName,
            KotlinCompilerIntegrationArtifacts.Prepared artifacts) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : artifacts.applicationClasspath()) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            return Class.forName(className, true, loader).getMethod(methodName).invoke(null);
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

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "generated-kotlin-main"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.kotlin]
                version = "2.2.0"

                [generated.main.prebuilt]
                kind = "declared-root"
                language = "kotlin"
                output = "generated/main"
                inputs = ["schema/generated.marker"]
                required = true
                clean = false

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """);
    }
}
