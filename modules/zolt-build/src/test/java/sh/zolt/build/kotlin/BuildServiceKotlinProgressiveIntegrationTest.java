package sh.zolt.build.kotlin;

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

/** Real compiler proof for Kotlin progressive-mode reuse boundaries. */
final class BuildServiceKotlinProgressiveIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void compilesProgressivelyAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        Path source = projectDir.resolve("src/main/kotlin/com/example/ProgressiveApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object ProgressiveApi {
                    @JvmStatic
                    fun message(): String = "progressive"
                }
                """);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals("progressive", invoke(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult progressive = build(service, true);
        assertFalse(progressive.mainCompilationSkipped());
        assertEquals("progressive", invoke(artifacts.applicationClasspath()));

        BuildResult progressiveWarm = build(service, true);
        assertTrue(progressiveWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("progressive", invoke(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, boolean progressive) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(progressive),
                        cacheRoot,
                        true)
                .buildResult();
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
            return Class.forName("com.example.ProgressiveApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private static ProjectConfig config(boolean progressive) {
        String compilerArgument = progressive
                ? "\"-parameters\", \"-progressive\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-progressive"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = [%s]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(compilerArgument));
    }
}
