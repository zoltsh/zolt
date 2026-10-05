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
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for diagnostic-specific Kotlin warning levels. */
final class BuildServiceKotlinWarningLevelIntegrationTest {
    private static final String DIAGNOSTIC = "REDUNDANT_VISIBILITY_MODIFIER";

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void overridesOneExtraWarningAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        Path source = projectDir.resolve("src/main/kotlin/com/example/WarningLevelApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                public object WarningLevelApi {
                    @JvmStatic
                    public fun message(): String = "granular"
                }
                """);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, List.of("-Wextra"));
        assertFalse(baseline.mainCompilationSkipped());
        assertTrue(diagnostics(baseline.compilerOutput()).contains("redundant visibility modifier"));
        assertEquals("granular", invoke(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, List.of("-Wextra"));
        assertTrue(baselineWarm.mainCompilationSkipped());

        KotlinCompileException promoted = assertThrows(
                KotlinCompileException.class,
                () -> build(service, List.of(
                        "-Wextra",
                        "-Xwarning-level=" + DIAGNOSTIC + ":error")));
        assertTrue(diagnostics(promoted.getMessage()).contains("redundant visibility modifier"));

        BuildResult disabled = build(service, List.of(
                "-Wextra",
                "-Werror",
                "-Xwarning-level=" + DIAGNOSTIC + ":disabled"));
        assertFalse(disabled.mainCompilationSkipped());
        assertFalse(diagnostics(disabled.compilerOutput()).contains("redundant visibility modifier"));
        assertEquals("granular", invoke(artifacts.applicationClasspath()));

        BuildResult disabledWarm = build(service, List.of(
                "-Wextra",
                "-Werror",
                "-Xwarning-level=" + DIAGNOSTIC + ":disabled"));
        assertTrue(disabledWarm.mainCompilationSkipped());

        BuildResult restored = build(service, List.of(
                "-Wextra",
                "-Xwarning-level=" + DIAGNOSTIC + ":warning"));
        assertFalse(restored.mainCompilationSkipped());
        assertTrue(diagnostics(restored.compilerOutput()).contains("redundant visibility modifier"));
        assertEquals("granular", invoke(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, List<String> arguments) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(arguments),
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
            return Class.forName("com.example.WarningLevelApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private static String diagnostics(String output) {
        return output.toLowerCase(Locale.ROOT);
    }

    private static ProjectConfig config(List<String> arguments) {
        String compilerArguments = arguments.stream()
                .map(argument -> "\"" + argument + "\"")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-warning-level"
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
