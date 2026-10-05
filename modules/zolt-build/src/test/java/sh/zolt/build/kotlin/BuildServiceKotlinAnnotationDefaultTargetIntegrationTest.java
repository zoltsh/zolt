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

/** Real compiler proof for Kotlin annotation default-target modes. */
final class BuildServiceKotlinAnnotationDefaultTargetIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void changesRuntimeAnnotationPlacementAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult firstOnly = build(service, "first-only");
        assertFalse(firstOnly.mainCompilationSkipped());
        assertEquals("true|false", placement(artifacts.applicationClasspath()));

        BuildResult firstOnlyWarm = build(service, "first-only");
        assertTrue(firstOnlyWarm.mainCompilationSkipped());

        BuildResult paramProperty = build(service, "param-property");
        assertFalse(paramProperty.mainCompilationSkipped());
        assertEquals("true|true", placement(artifacts.applicationClasspath()));

        BuildResult paramPropertyWarm = build(service, "param-property");
        assertTrue(paramPropertyWarm.mainCompilationSkipped());

        BuildResult restored = build(service, "first-only");
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("true|false", placement(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, String mode) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(mode),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/AnnotationDefaultApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Marker

                data class Annotated(@Marker val value: String)

                object AnnotationDefaultApi {
                    @JvmStatic
                    fun placement(): String {
                        val type = Annotated::class.java
                        val parameter = type.getDeclaredConstructor(String::class.java)
                            .parameters[0]
                            .isAnnotationPresent(Marker::class.java)
                        val field = type.getDeclaredField("value")
                            .isAnnotationPresent(Marker::class.java)
                        return "$parameter|$field"
                    }
                }
                """);
    }

    private Object placement(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            return Class.forName("com.example.AnnotationDefaultApi", true, loader)
                    .getMethod("placement")
                    .invoke(null);
        }
    }

    private static ProjectConfig config(String mode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-annotation-default-target"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = ["-Xannotation-default-target=%s"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(mode));
    }
}
