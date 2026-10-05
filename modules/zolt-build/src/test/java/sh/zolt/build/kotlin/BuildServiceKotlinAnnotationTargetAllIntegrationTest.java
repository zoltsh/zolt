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

/** Real compiler proof for Kotlin's all-target annotation preview. */
final class BuildServiceKotlinAnnotationTargetAllIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void propagatesAllTargetOnlyWhenPreviewIsEnabled() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        KotlinCompileException disabled = assertThrows(
                KotlinCompileException.class,
                () -> build(service, false));
        assertTrue(diagnostics(disabled).contains("-xannotation-target-all"), disabled.getMessage());

        BuildResult preview = build(service, true);
        assertFalse(preview.mainCompilationSkipped());
        assertEquals("true|true|true|true", placement(artifacts.applicationClasspath()));

        BuildResult previewWarm = build(service, true);
        assertTrue(previewWarm.mainCompilationSkipped());

        KotlinCompileException removed = assertThrows(
                KotlinCompileException.class,
                () -> build(service, false));
        assertTrue(diagnostics(removed).contains("-xannotation-target-all"), removed.getMessage());

        BuildResult restored = build(service, true);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("true|true|true|true", placement(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, boolean preview) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(preview),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/AnnotationTargetAllApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @Target(
                    AnnotationTarget.PROPERTY,
                    AnnotationTarget.FIELD,
                    AnnotationTarget.VALUE_PARAMETER,
                    AnnotationTarget.PROPERTY_GETTER,
                )
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Spread

                class Annotated(@all:Spread var value: String)

                object AnnotationTargetAllApi {
                    @JvmStatic
                    fun placement(): String {
                        val type = Annotated::class.java
                        val parameter = type.getDeclaredConstructor(String::class.java)
                            .parameters[0]
                            .isAnnotationPresent(Spread::class.java)
                        val field = type.getDeclaredField("value")
                            .isAnnotationPresent(Spread::class.java)
                        val getter = type.getDeclaredMethod("getValue")
                            .isAnnotationPresent(Spread::class.java)
                        val setterParameter = type.getDeclaredMethod("setValue", String::class.java)
                            .parameters[0]
                            .isAnnotationPresent(Spread::class.java)
                        return "$parameter|$field|$getter|$setterParameter"
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
            return Class.forName("com.example.AnnotationTargetAllApi", true, loader)
                    .getMethod("placement")
                    .invoke(null);
        }
    }

    private static String diagnostics(KotlinCompileException exception) {
        return exception.getMessage().toLowerCase(Locale.ROOT);
    }

    private static ProjectConfig config(boolean preview) {
        String compilerArguments = preview
                ? "\"-parameters\", \"-Xannotation-target-all\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-annotation-target-all"
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
