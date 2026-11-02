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
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for Kotlin warning suppression and reuse boundaries. */
final class BuildServiceKotlinWarningSuppressionIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void suppressesCompilerWarningsAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        Path source = projectDir.resolve("src/main/kotlin/com/example/SuppressedApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @Deprecated("use message")
                fun oldValue(): String = "quiet"

                object SuppressedApi {
                    @JvmStatic
                    fun message(): String = oldValue()
                }
                """);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertTrue(diagnostics(baseline).contains("deprecated"), baseline.compilerOutput());
        assertEquals("quiet", invoke(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult suppressed = build(service, true);
        assertFalse(suppressed.mainCompilationSkipped());
        assertFalse(diagnostics(suppressed).contains("deprecated"), suppressed.compilerOutput());
        assertEquals("quiet", invoke(artifacts.applicationClasspath()));

        BuildResult suppressedWarm = build(service, true);
        assertTrue(suppressedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertTrue(diagnostics(restored).contains("deprecated"), restored.compilerOutput());
        assertEquals("quiet", invoke(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, boolean suppressWarnings) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(suppressWarnings),
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
            return Class.forName("com.example.SuppressedApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private static String diagnostics(BuildResult result) {
        return result.compilerOutput().toLowerCase(Locale.ROOT);
    }

    private static ProjectConfig config(boolean suppressWarnings) {
        String compilerArguments = suppressWarnings
                ? "\"-parameters\", \"-nowarn\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-warning-suppression"
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
                """.formatted(compilerArguments));
    }
}
