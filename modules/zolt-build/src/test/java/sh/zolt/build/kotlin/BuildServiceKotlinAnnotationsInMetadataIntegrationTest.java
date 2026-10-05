package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for declaration annotations in Kotlin metadata. */
final class BuildServiceKotlinAnnotationsInMetadataIntegrationTest {
    private static final List<String> ANNOTATION_METADATA = List.of(
            "Lcom/example/Marker;",
            "class-payload",
            "method-payload");

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void writesAnnotationsIntoMetadataAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertObservation(artifacts.applicationClasspath(), false);
        byte[] baselineBytes = classFileBytes();

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult annotated = build(service, true);
        assertFalse(annotated.mainCompilationSkipped());
        assertObservation(artifacts.applicationClasspath(), true);
        assertFalse(Arrays.equals(baselineBytes, classFileBytes()));

        BuildResult annotatedWarm = build(service, true);
        assertTrue(annotatedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertObservation(artifacts.applicationClasspath(), false);
        assertArrayEquals(baselineBytes, classFileBytes());
    }

    private BuildResult build(BuildService service, boolean annotationsInMetadata) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(annotationsInMetadata),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/AnnotatedApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
                @Retention(AnnotationRetention.BINARY)
                annotation class Marker(val value: String)

                @Marker("class-payload")
                object AnnotatedApi {
                    @Marker("method-payload")
                    @JvmStatic
                    fun answer(): Int = 42
                }
                """);
    }

    private void assertObservation(
            List<Path> applicationClasspath,
            boolean expectedInMetadata) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName("com.example.AnnotatedApi", true, loader);
            assertEquals(42, type.getMethod("answer").invoke(null));
            Class<? extends Annotation> metadataType = Class.forName(
                            "kotlin.Metadata",
                            true,
                            loader)
                    .asSubclass(Annotation.class);
            Annotation metadata = type.getAnnotation(metadataType);
            assertNotNull(metadata);
            List<String> data2 = Arrays.asList(
                    (String[]) metadataType.getMethod("d2").invoke(metadata));
            for (String value : ANNOTATION_METADATA) {
                assertEquals(expectedInMetadata, data2.contains(value), data2.toString());
            }
        }
    }

    private byte[] classFileBytes() throws Exception {
        return Files.readAllBytes(projectDir.resolve(
                "target/classes/com/example/AnnotatedApi.class"));
    }

    private static ProjectConfig config(boolean annotationsInMetadata) {
        String compilerArguments = annotationsInMetadata
                ? "\"-parameters\", \"-Xannotations-in-metadata\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-annotations-in-metadata"
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
