package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Real exec generator, Kotlin compiler, and cache lifecycle for an owned Kotlin main source. */
final class BuildServiceExecGeneratedKotlinIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @TempDir
    private Path buildCacheHome;

    @Test
    void generatesCompilesCachesAndInvalidatesKotlinMainSources() throws Exception {
        Path generatorJar = ExecToolJarFixture.generatorJar(projectDir.resolve("fixture-work"));
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepareWithExecTool(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"),
                        generatorJar);
        source("src/main/gen/config.txt", "generate Kotlin\n");
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
        BuildService service = new BuildService().withBuildCache(BuildCacheService.create(
                new BuildCacheSettings(true, buildCacheHome, 0L),
                "exec-generated-kotlin-main-integration"));

        BuildResult first = service.buildWithClasspaths(projectDir, config("v1"), cacheRoot, true)
                .buildResult();

        assertEquals(2, first.sourceCount());
        assertEquals("full", first.mainCompilationMode());
        assertEquals("kotlin-main-sources", first.mainIncrementalFallbackReason());
        assertEquals("stored", first.mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(generatedSource()));
        assertTrue(Files.isRegularFile(classFile("GeneratedKotlin.class")));
        assertEquals("java-v1", invoke("com.example.JavaApi", "callGenerated", artifacts));

        BuildResult warm = service.buildWithClasspaths(projectDir, config("v1"), cacheRoot, true)
                .buildResult();
        assertTrue(warm.mainCompilationSkipped());

        deleteTree(projectDir.resolve("target"));
        BuildResult restored = service.buildWithClasspaths(projectDir, config("v1"), cacheRoot, true)
                .buildResult();
        assertTrue(restored.mainCompilationRestored());
        assertTrue(Files.isRegularFile(generatedSource()));
        assertEquals("java-v1", invoke("com.example.JavaApi", "callGenerated", artifacts));

        BuildResult changed = service.buildWithClasspaths(projectDir, config("v2"), cacheRoot, true)
                .buildResult();
        assertFalse(changed.mainCompilationSkipped());
        assertFalse(changed.mainCompilationRestored());
        assertEquals("full", changed.mainCompilationMode());
        assertEquals("java-v2", invoke("com.example.GeneratedKotlin", "message", artifacts));
        assertEquals("java-v2", invoke("com.example.JavaApi", "callGenerated", artifacts));
    }

    private Path generatedSource() {
        return projectDir.resolve("target/generated/sources/gen/com/example/GeneratedKotlin.kt");
    }

    private Path classFile(String name) {
        return projectDir.resolve("target/classes/com/example").resolve(name);
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

    private static ProjectConfig config(String revision) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "exec-generated-kotlin-main"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.kotlin]
                version = "2.2.0"

                [generated.tools.gen-tool]
                kind = "jvm"
                coordinates = [{ coordinate = "com.example:gen-tool", version = "1.0.0" }]
                mainClass = "com.example.tool.GenTool"

                [generated.main.model]
                kind = "exec"
                language = "kotlin"
                tool = "gen-tool"
                args = [
                    "com/example/GeneratedKotlin.kt",
                    '''
                package com.example

                object GeneratedKotlin {
                    @JvmStatic
                    fun message(): String = JavaApi.value() + "-%s"
                }
                ''',
                ]
                inputs = ["src/main/gen/config.txt"]
                output = "target/generated/sources/gen"
                produces = "java-sources"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(revision));
    }
}
