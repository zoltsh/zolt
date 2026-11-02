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

/** Real compiler proof that Kotlin extra checks compose with warning-as-error policy. */
final class BuildServiceKotlinExtraWarningsIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void enablesExtraDiagnosticsAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        Path source = projectDir.resolve("src/main/kotlin/com/example/WarningApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                public object WarningApi {
                    @JvmStatic
                    public fun message(): String = "extra"
                }
                """);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, List.of("-Werror"));
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals("extra", invoke(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, List.of("-Werror"));
        assertTrue(baselineWarm.mainCompilationSkipped());

        KotlinCompileException strict = assertThrows(
                KotlinCompileException.class,
                () -> build(service, List.of("-Wextra", "-Werror")));
        assertTrue(strict.getMessage().contains("redundant visibility modifier"), strict.getMessage());

        BuildResult warningsAllowed = build(service, List.of("-Wextra"));
        assertFalse(warningsAllowed.mainCompilationSkipped());
        assertEquals("extra", invoke(artifacts.applicationClasspath()));

        BuildResult warningsAllowedWarm = build(service, List.of("-Wextra"));
        assertTrue(warningsAllowedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, List.of("-Werror"));
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("extra", invoke(artifacts.applicationClasspath()));
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
            return Class.forName("com.example.WarningApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private static ProjectConfig config(List<String> arguments) {
        String compilerArguments = arguments.stream()
                .map(argument -> "\"" + argument + "\"")
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-extra-warnings"
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
