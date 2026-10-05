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

/** Real Kotlin 2.2 compiler proof for explicit when-guards configuration. */
final class BuildServiceKotlinWhenGuardsIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void explicitFlagCompilesAndInvalidatesReuseForStableWhenGuards() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals("positive-2|nonpositive|failed", invoke(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult explicit = build(service, true);
        assertFalse(explicit.mainCompilationSkipped());
        assertEquals("positive-2|nonpositive|failed", invoke(artifacts.applicationClasspath()));

        BuildResult explicitWarm = build(service, true);
        assertTrue(explicitWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("positive-2|nonpositive|failed", invoke(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, boolean explicitFlag) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(explicitFlag),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/WhenGuardsApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                sealed interface Status
                data class Success(val value: Int) : Status
                data class Failure(val message: String) : Status

                private fun render(status: Status): String = when (status) {
                    is Success if status.value > 0 -> "positive-${status.value}"
                    is Success -> "nonpositive"
                    is Failure -> status.message
                }

                object WhenGuardsApi {
                    @JvmStatic
                    fun message(): String = listOf(
                        render(Success(2)),
                        render(Success(0)),
                        render(Failure("failed")),
                    ).joinToString("|")
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
            return Class.forName("com.example.WhenGuardsApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private static ProjectConfig config(boolean explicitFlag) {
        String compilerArguments = explicitFlag
                ? "\"-parameters\", \"-Xwhen-guards\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-when-guards"
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
