package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for module-wide boxed value-class exposure. */
final class BuildServiceKotlinJvmExposeBoxedIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void changesJavaInteropSurfaceAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals(new Surface(false, false), surface(artifacts.applicationClasspath()));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult preview = build(service, true);
        assertFalse(preview.mainCompilationSkipped());
        assertEquals(new Surface(true, true), surface(artifacts.applicationClasspath()));

        BuildResult previewWarm = build(service, true);
        assertTrue(previewWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals(new Surface(false, false), surface(artifacts.applicationClasspath()));
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
        Path source = projectDir.resolve("src/main/kotlin/com/example/BoxedApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @JvmInline
                value class UserId(val value: String)

                fun echo(value: UserId): UserId = value
                """);
    }

    private Surface surface(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> userId = Class.forName("com.example.UserId", false, loader);
            Class<?> api = Class.forName("com.example.BoxedApiKt", false, loader);
            boolean publicConstructor = Arrays.stream(userId.getConstructors())
                    .anyMatch(constructor -> Arrays.equals(
                            constructor.getParameterTypes(),
                            new Class<?>[] {String.class}));
            boolean boxedFunction = Arrays.stream(api.getMethods())
                    .anyMatch(method -> method.getName().equals("echo")
                            && method.getReturnType().equals(userId)
                            && Arrays.equals(
                                    method.getParameterTypes(),
                                    new Class<?>[] {userId}));
            return new Surface(publicConstructor, boxedFunction);
        }
    }

    private static ProjectConfig config(boolean preview) {
        String compilerArguments = preview
                ? "\"-parameters\", \"-Xjvm-expose-boxed\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-jvm-expose-boxed"
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

    private record Surface(boolean publicConstructor, boolean boxedFunction) {
    }
}
