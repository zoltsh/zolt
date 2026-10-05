package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
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

/** Real compiler proof for Kotlin metadata strict-version semantics. */
final class BuildServiceKotlinStrictMetadataIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void marksStrictMetadataAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertObservation(artifacts.applicationClasspath(), 48);

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult strict = build(service, true);
        assertFalse(strict.mainCompilationSkipped());
        assertObservation(artifacts.applicationClasspath(), 56);

        BuildResult strictWarm = build(service, true);
        assertTrue(strictWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertObservation(artifacts.applicationClasspath(), 48);
    }

    private BuildResult build(BuildService service, boolean strictMetadata) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(strictMetadata),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/MetadataApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object MetadataApi {
                    @JvmStatic
                    fun message(): String = "metadata"
                }
                """);
    }

    private void assertObservation(
            List<Path> applicationClasspath,
            int expectedExtraInt) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName("com.example.MetadataApi", true, loader);
            assertEquals("metadata", type.getMethod("message").invoke(null));
            Class<? extends Annotation> metadataType = Class.forName(
                            "kotlin.Metadata",
                            true,
                            loader)
                    .asSubclass(Annotation.class);
            Annotation metadata = type.getAnnotation(metadataType);
            assertNotNull(metadata);
            assertEquals(expectedExtraInt, metadataType.getMethod("xi").invoke(metadata));
        }
    }

    private static ProjectConfig config(boolean strictMetadata) {
        String compilerArguments = strictMetadata
                ? "\"-parameters\", \"-Xgenerate-strict-metadata-version\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-strict-metadata"
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
