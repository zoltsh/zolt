package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Real compiler proof for Kotlin type-use annotations in JVM class files. */
final class BuildServiceKotlinJvmTypeAnnotationsIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void changesReflectedReturnTypeAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertFalse(returnTypeIsTagged(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult enabled = build(service, true);
        assertFalse(enabled.mainCompilationSkipped());
        assertTrue(returnTypeIsTagged(artifacts.applicationClasspath()));

        BuildResult enabledWarm = build(service, true);
        assertTrue(enabledWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertFalse(returnTypeIsTagged(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, boolean emitTypeAnnotations) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(emitTypeAnnotations),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/TypeApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @Target(AnnotationTarget.TYPE)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Tagged

                class TypeApi {
                    fun value(): @Tagged String = "Zolt"
                }
                """);
    }

    private boolean returnTypeIsTagged(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<? extends Annotation> tagged = Class.forName(
                            "com.example.Tagged",
                            false,
                            loader)
                    .asSubclass(Annotation.class);
            return Class.forName("com.example.TypeApi", false, loader)
                    .getDeclaredMethod("value")
                    .getAnnotatedReturnType()
                    .isAnnotationPresent(tagged);
        }
    }

    private static ProjectConfig config(boolean emitTypeAnnotations) {
        String compilerArguments = emitTypeAnnotations
                ? "\"-parameters\", \"-Xemit-jvm-type-annotations\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-jvm-type-annotations"
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
