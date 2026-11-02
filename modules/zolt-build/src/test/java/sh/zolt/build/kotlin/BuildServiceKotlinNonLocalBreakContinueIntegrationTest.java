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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for non-local loop control across Kotlin language modes. */
final class BuildServiceKotlinNonLocalBreakContinueIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void enablesThePreviewForLanguage21AndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        KotlinCompileException unavailable = assertThrows(
                KotlinCompileException.class,
                () -> build(service, "2.1", false));
        assertTrue(
                unavailable.getMessage().contains(
                        "break continue in inline lambdas\" is only available since language version 2.2"),
                unavailable.getMessage());

        BuildResult preview = build(service, "2.1", true);
        assertFalse(preview.mainCompilationSkipped());
        assertEquals("-2,3", collect(artifacts.applicationClasspath()));

        BuildResult previewWarm = build(service, "2.1", true);
        assertTrue(previewWarm.mainCompilationSkipped());

        BuildResult stable = build(service, "2.2", false);
        assertFalse(stable.mainCompilationSkipped());
        assertEquals("-2,3", collect(artifacts.applicationClasspath()));

        BuildResult stableWarm = build(service, "2.2", false);
        assertTrue(stableWarm.mainCompilationSkipped());

        BuildResult restored = build(service, "2.1", true);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("-2,3", collect(artifacts.applicationClasspath()));
    }

    private BuildResult build(
            BuildService service,
            String languageVersion,
            boolean enablePreview) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(languageVersion, enablePreview),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve(
                "src/main/kotlin/com/example/NonLocalLoopControlApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object NonLocalLoopControlApi {
                    @JvmStatic
                    fun collect(values: List<Int?>): String {
                        val accepted = mutableListOf<Int>()
                        for (element in values) {
                            val value = element ?: run {
                                continue
                            }
                            if (value == 0) {
                                run {
                                    break
                                }
                            }
                            accepted += value
                        }
                        return accepted.joinToString(",")
                    }
                }
                """);
    }

    private String collect(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            return (String) Class.forName("com.example.NonLocalLoopControlApi", true, loader)
                    .getMethod("collect", List.class)
                    .invoke(null, Arrays.asList(null, -2, 3, null, 0, 9));
        }
    }

    private static ProjectConfig config(
            String languageVersion,
            boolean enablePreview) {
        String previewArgument = enablePreview
                ? ", \"-Xnon-local-break-continue\""
                : "";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-non-local-loop-control"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = ["-language-version", "%s"%s]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(languageVersion, previewArgument));
    }
}
