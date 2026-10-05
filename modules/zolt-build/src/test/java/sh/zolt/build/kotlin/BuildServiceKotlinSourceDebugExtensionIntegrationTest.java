package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
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

/** Real compiler proof for suppressing Kotlin's source-debug-extension annotation copy. */
final class BuildServiceKotlinSourceDebugExtensionIntegrationTest {
    private static final String ANNOTATION_DESCRIPTOR =
            "kotlin/jvm/internal/SourceDebugExtension";

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void removesOnlyTheAnnotationCopyAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals("debug", invoke(artifacts.applicationClasspath()));
        assertTrue(classFileText().contains("SMAP"));
        assertTrue(classFileText().contains(ANNOTATION_DESCRIPTOR));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult suppressed = build(service, true);
        assertFalse(suppressed.mainCompilationSkipped());
        assertEquals("debug", invoke(artifacts.applicationClasspath()));
        assertTrue(classFileText().contains("SMAP"));
        assertFalse(classFileText().contains(ANNOTATION_DESCRIPTOR));

        BuildResult suppressedWarm = build(service, true);
        assertTrue(suppressedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("debug", invoke(artifacts.applicationClasspath()));
        assertTrue(classFileText().contains("SMAP"));
        assertTrue(classFileText().contains(ANNOTATION_DESCRIPTOR));
    }

    private BuildResult build(BuildService service, boolean suppressAnnotation) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(suppressAnnotation),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/DebugApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                private inline fun <T> through(block: () -> T): T = block()

                object DebugApi {
                    @JvmStatic
                    fun message(): String = through {
                        "debug"
                    }
                }
                """);
    }

    private String invoke(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            return (String) Class.forName("com.example.DebugApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private String classFileText() throws Exception {
        byte[] bytes = Files.readAllBytes(projectDir.resolve(
                "target/classes/com/example/DebugApi.class"));
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static ProjectConfig config(boolean suppressAnnotation) {
        String compilerArguments = suppressAnnotation
                ? "\"-parameters\", \"-Xno-source-debug-extension\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-source-debug-extension"
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
