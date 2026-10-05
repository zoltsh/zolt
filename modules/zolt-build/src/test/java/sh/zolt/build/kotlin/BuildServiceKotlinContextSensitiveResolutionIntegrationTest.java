package sh.zolt.build.kotlin;

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
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for Kotlin 2.2 context-sensitive resolution. */
final class BuildServiceKotlinContextSensitiveResolutionIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void resolvesContextualEnumEntriesOnlyWhenPreviewIsEnabled() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source(true);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals("connection", invoke(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        source(false);
        KotlinCompileException disabled = assertThrows(
                KotlinCompileException.class,
                () -> build(service, false));
        assertTrue(disabled.getMessage().contains("CONNECTION"), disabled.getMessage());

        BuildResult preview = build(service, true);
        assertFalse(preview.mainCompilationSkipped());
        assertEquals("connection", invoke(artifacts.applicationClasspath()));

        BuildResult previewWarm = build(service, true);
        assertTrue(previewWarm.mainCompilationSkipped());

        KotlinCompileException removed = assertThrows(
                KotlinCompileException.class,
                () -> build(service, false));
        assertTrue(removed.getMessage().contains("CONNECTION"), removed.getMessage());

        BuildResult restored = build(service, true);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("connection", invoke(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, boolean preview) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(preview),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source(boolean qualified) throws Exception {
        String connection = qualified ? "Problem.CONNECTION" : "CONNECTION";
        String authentication = qualified ? "Problem.AUTHENTICATION" : "AUTHENTICATION";
        Path source = projectDir.resolve("src/main/kotlin/com/example/ContextApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                enum class Problem { CONNECTION, AUTHENTICATION }

                object ContextApi {
                    @JvmStatic
                    fun message(problem: Problem): String = when (problem) {
                        %s -> "connection"
                        %s -> "authentication"
                    }
                }
                """.formatted(connection, authentication));
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
            Class<?> problem = Class.forName("com.example.Problem", true, loader);
            Object connection = problem.getEnumConstants()[0];
            return Class.forName("com.example.ContextApi", true, loader)
                    .getMethod("message", problem)
                    .invoke(null, connection);
        }
    }

    private static ProjectConfig config(boolean preview) {
        String compilerArguments = preview
                ? "\"-parameters\", \"-Xcontext-sensitive-resolution\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-context-sensitive-resolution"
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
