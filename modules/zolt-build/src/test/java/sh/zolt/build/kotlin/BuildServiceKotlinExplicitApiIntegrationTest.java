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

/** Real compiler proof for Kotlin explicit-API modes and reuse boundaries. */
final class BuildServiceKotlinExplicitApiIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void reportsImplicitApiAndAcceptsCorrectedStrictApi() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source(false);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, "");
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals("explicit", invoke(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, "");
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult warning = build(service, "warning");
        assertFalse(warning.mainCompilationSkipped());
        assertTrue(diagnostics(warning.compilerOutput()).contains("explicit api mode"), warning.compilerOutput());
        assertEquals("explicit", invoke(artifacts.applicationClasspath()));

        BuildResult warningWarm = build(service, "warning");
        assertTrue(warningWarm.mainCompilationSkipped());

        KotlinCompileException strict = assertThrows(
                KotlinCompileException.class,
                () -> build(service, "strict"));
        assertTrue(diagnostics(strict.getMessage()).contains("explicit api mode"), strict.getMessage());

        source(true);
        BuildResult corrected = build(service, "strict");
        assertFalse(corrected.mainCompilationSkipped());
        assertEquals("explicit", invoke(artifacts.applicationClasspath()));

        BuildResult correctedWarm = build(service, "strict");
        assertTrue(correctedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, "");
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("explicit", invoke(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, String mode) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(mode),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source(boolean explicit) throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/ExplicitApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, explicit
                ? """
                        package com.example

                        public object ExplicitApi {
                            @JvmStatic
                            public fun message(): String = "explicit"
                        }
                        """
                : """
                        package com.example

                        object ExplicitApi {
                            @JvmStatic
                            fun message() = "explicit"
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
            return Class.forName("com.example.ExplicitApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private static String diagnostics(String output) {
        return output.toLowerCase(Locale.ROOT);
    }

    private static ProjectConfig config(String mode) {
        String compilerArguments = mode.isEmpty()
                ? "\"-parameters\""
                : "\"-parameters\", \"-Xexplicit-api=" + mode + "\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-explicit-api"
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
