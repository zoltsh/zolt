package sh.zolt.build.kotlin.compatibility.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real-compiler proof for bounded Kotlin backend parallelism and reuse boundaries. */
final class BuildServiceKotlinBackendThreadsIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void compilesAcrossMultipleBackendThreadsAndInvalidatesCountChanges() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        writeSources();
        BuildService service = new BuildService();

        BuildResult serial = build(service, 1);
        assertFalse(serial.mainCompilationSkipped());
        assertEquals("alpha-beta", invoke(artifacts.applicationClasspath()));

        BuildResult serialWarm = build(service, 1);
        assertTrue(serialWarm.mainCompilationSkipped());

        BuildResult parallel = build(service, 2);
        assertFalse(parallel.mainCompilationSkipped());
        assertEquals("alpha-beta", invoke(artifacts.applicationClasspath()));

        BuildResult parallelWarm = build(service, 2);
        assertTrue(parallelWarm.mainCompilationSkipped());

        BuildResult restored = build(service, 1);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("alpha-beta", invoke(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, int backendThreads) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(backendThreads),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void writeSources() throws Exception {
        Path sources = projectDir.resolve("src/main/kotlin/com/example");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve("Alpha.kt"), """
                package com.example

                object Alpha {
                    fun value(): String = "alpha"
                }
                """);
        Files.writeString(sources.resolve("Beta.kt"), """
                package com.example

                object Beta {
                    fun value(): String = "beta"
                }
                """);
        Files.writeString(sources.resolve("ParallelApi.kt"), """
                package com.example

                object ParallelApi {
                    @JvmStatic
                    fun value(): String = Alpha.value() + "-" + Beta.value()
                }
                """);
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
            return Class.forName("com.example.ParallelApi", true, loader)
                    .getMethod("value")
                    .invoke(null);
        }
    }

    private static ProjectConfig config(int backendThreads) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-backend-threads"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = ["-Xbackend-threads=%d"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(backendThreads));
    }
}
