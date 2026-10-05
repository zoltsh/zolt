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

/** Real compiler proof for Kotlin/JVM assertion code-generation modes. */
final class BuildServiceKotlinAssertionModeIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void changesEvaluationAndFailureBehaviorAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult alwaysEnabled = build(service, "always-enable");
        assertFalse(alwaysEnabled.mainCompilationSkipped());
        assertEquals("threw:1", behavior(artifacts.applicationClasspath(), false));

        BuildResult alwaysEnabledWarm = build(service, "always-enable");
        assertTrue(alwaysEnabledWarm.mainCompilationSkipped());

        BuildResult alwaysDisabled = build(service, "always-disable");
        assertFalse(alwaysDisabled.mainCompilationSkipped());
        assertEquals("passed:0", behavior(artifacts.applicationClasspath(), false));
        assertEquals("passed:0", behavior(artifacts.applicationClasspath(), true));

        BuildResult jvm = build(service, "jvm");
        assertFalse(jvm.mainCompilationSkipped());
        assertEquals("passed:0", behavior(artifacts.applicationClasspath(), false));
        assertEquals("threw:1", behavior(artifacts.applicationClasspath(), true));

        BuildResult legacy = build(service, "legacy");
        assertFalse(legacy.mainCompilationSkipped());
        assertEquals("passed:1", behavior(artifacts.applicationClasspath(), false));
        assertEquals("threw:1", behavior(artifacts.applicationClasspath(), true));

        BuildResult legacyWarm = build(service, "legacy");
        assertTrue(legacyWarm.mainCompilationSkipped());

        BuildResult restored = build(service, "always-enable");
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("threw:1", behavior(artifacts.applicationClasspath(), false));
    }

    private BuildResult build(BuildService service, String assertionMode) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(assertionMode),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/AssertionApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object AssertionApi {
                    private var evaluations = 0

                    @JvmStatic
                    fun behavior(): String {
                        evaluations = 0
                        return try {
                            assert(recordFalse()) { "failed" }
                            "passed:$evaluations"
                        } catch (_: AssertionError) {
                            "threw:$evaluations"
                        }
                    }

                    private fun recordFalse(): Boolean {
                        evaluations++
                        return false
                    }
                }
                """);
    }

    private String behavior(
            List<Path> applicationClasspath,
            boolean assertionsEnabled) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            loader.setDefaultAssertionStatus(assertionsEnabled);
            return (String) Class.forName("com.example.AssertionApi", true, loader)
                    .getMethod("behavior")
                    .invoke(null);
        }
    }

    private static ProjectConfig config(String assertionMode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-assertion-mode"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = ["-Xassertions=%s"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(assertionMode));
    }
}
