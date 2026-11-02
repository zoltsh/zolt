package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real OpenAPI tool, mixed Java/Kotlin compiler, output-integrity, and cache lifecycle. */
final class BuildServiceOpenApiGeneratedKotlinIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @TempDir
    private Path buildCacheHome;

    @Test
    void generatesCompilesRepairsAndInvalidatesKotlinMainSources() throws Exception {
        Path generatorJar = OpenApiToolJarFixture.generatorJar(projectDir.resolve("fixture-work"));
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepareWithOpenApiTool(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"),
                        generatorJar);
        source("src/main/openapi/client.yaml", "openapi: 3.1.0\n");
        source("src/main/java/com/example/JavaApi.java", """
                package com.example;

                public final class JavaApi {
                    public static String callGenerated() {
                        return GeneratedClient.message();
                    }
                }
                """);
        BuildService service = new BuildService().withBuildCache(BuildCacheService.create(
                new BuildCacheSettings(true, buildCacheHome, 0L),
                "openapi-generated-kotlin-main-integration"));

        BuildResult first = service.buildWithClasspaths(projectDir, config("v1"), cacheRoot, true)
                .buildResult();

        assertEquals(2, first.sourceCount());
        assertEquals("full", first.mainCompilationMode());
        assertEquals("kotlin-main-sources", first.mainIncrementalFallbackReason());
        assertEquals("stored", first.mainBuildCacheOutcome());
        assertTrue(Files.isRegularFile(generatedSource()));
        assertTrue(Files.isRegularFile(classFile("GeneratedClient.class")));
        assertEquals("openapi-v1", invoke("com.example.JavaApi", "callGenerated", artifacts));
        assertEquals(List.of("v1"), invocations());

        BuildResult warm = service.buildWithClasspaths(projectDir, config("v1"), cacheRoot, true)
                .buildResult();
        assertTrue(warm.mainCompilationSkipped());
        assertEquals(List.of("v1"), invocations());

        Files.writeString(generatedSource(), "not Kotlin\n");
        BuildResult repaired = service.buildWithClasspaths(projectDir, config("v1"), cacheRoot, true)
                .buildResult();
        assertTrue(repaired.mainCompilationSkipped());
        assertTrue(Files.readString(generatedSource()).contains("openapi-v1"));
        assertEquals("openapi-v1", invoke("com.example.JavaApi", "callGenerated", artifacts));
        assertEquals(List.of("v1", "v1"), invocations());

        BuildResult changed = service.buildWithClasspaths(projectDir, config("v2"), cacheRoot, true)
                .buildResult();
        assertFalse(changed.mainCompilationSkipped());
        assertFalse(changed.mainCompilationRestored());
        assertEquals("full", changed.mainCompilationMode());
        assertEquals("openapi-v2", invoke("com.example.GeneratedClient", "message", artifacts));
        assertEquals("openapi-v2", invoke("com.example.JavaApi", "callGenerated", artifacts));
        assertEquals(List.of("v1", "v1", "v2"), invocations());
    }

    private Path generatedSource() {
        return projectDir.resolve("target/generated/sources/openapi/client/com/example/GeneratedClient.kt");
    }

    private Path classFile(String name) {
        return projectDir.resolve("target/classes/com/example").resolve(name);
    }

    private List<String> invocations() throws IOException {
        return Files.readAllLines(projectDir.resolve("openapi-invocations.txt"));
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

    private static ProjectConfig config(String revision) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "openapi-generated-kotlin-main"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [toolchain.kotlin]
                version = "2.2.0"

                [generated.tools.openapi]
                coordinate = "org.openapitools:openapi-generator-cli"
                version = "7.11.0"

                [generated.main.client]
                kind = "openapi"
                language = "kotlin"
                input = "src/main/openapi/client.yaml"
                output = "target/generated/sources/openapi/client"
                generator = "kotlin"
                additionalProperties = { revision = "%s" }

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(revision));
    }
}
