package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for bounded Kotlin language and API version arguments. */
final class BuildServiceKotlinVersionArgumentsIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void enforcesLanguageAndApiVersionsAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source("src/main/kotlin/com/example/VersionedApi.kt", """
                package com.example

                data object Marker

                enum class Color { RED }

                object VersionedApi {
                    @JvmStatic
                    fun message(): String = Marker.toString() + ":" + Color.entries.size
                }
                """);
        BuildService service = new BuildService();

        KotlinCompileException languageFailure = assertThrows(
                KotlinCompileException.class,
                () -> service.buildWithClasspaths(
                        projectDir,
                        config("1.8", "1.8"),
                        cacheRoot,
                        true));
        assertTrue(languageFailure.getMessage().contains("language version 1.9"), languageFailure.getMessage());

        KotlinCompileException apiFailure = assertThrows(
                KotlinCompileException.class,
                () -> service.buildWithClasspaths(
                        projectDir,
                        config("1.9", "1.8"),
                        cacheRoot,
                        true));
        assertTrue(apiFailure.getMessage().contains("ExperimentalStdlibApi"), apiFailure.getMessage());

        BuildResult first = service.buildWithClasspaths(
                        projectDir,
                        config("1.9", "1.9"),
                        cacheRoot,
                        true)
                .buildResult();
        assertEquals("full", first.mainCompilationMode());
        assertEquals(1, first.sourceCount());
        assertEquals("Marker:1", invoke(artifacts.applicationClasspath()));

        BuildResult warm = service.buildWithClasspaths(
                        projectDir,
                        config("1.9", "1.9"),
                        cacheRoot,
                        true)
                .buildResult();
        assertTrue(warm.mainCompilationSkipped());

        KotlinCompileException downgraded = assertThrows(
                KotlinCompileException.class,
                () -> service.buildWithClasspaths(
                        projectDir,
                        config("1.9", "1.8"),
                        cacheRoot,
                        true));
        assertTrue(downgraded.getMessage().contains("ExperimentalStdlibApi"), downgraded.getMessage());

        BuildResult restored = service.buildWithClasspaths(
                        projectDir,
                        config("1.9", "1.9"),
                        cacheRoot,
                        true)
                .buildResult();
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("Marker:1", invoke(artifacts.applicationClasspath()));
    }

    private Object invoke(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            return Class.forName("com.example.VersionedApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private Path source(String relativePath, String content) throws Exception {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static ProjectConfig config(String languageVersion, String apiVersion) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-version-arguments"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = ["-language-version", "%s", "-api-version", "%s"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(languageVersion, apiVersion));
    }
}
