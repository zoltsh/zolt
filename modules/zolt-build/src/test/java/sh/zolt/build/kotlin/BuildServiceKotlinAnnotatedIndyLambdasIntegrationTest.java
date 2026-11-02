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

/** Real compiler proof for invokedynamic generation of annotated Kotlin lambdas. */
final class BuildServiceKotlinAnnotatedIndyLambdasIntegrationTest {
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final String MARKER_DESCRIPTOR = "com/example/Marker";

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void emitsAnnotatedLambdaAsIndyAndCleansFallbackClass() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult fallback = build(service, false);
        assertFalse(fallback.mainCompilationSkipped());
        assertEquals("annotated", invoke(artifacts.applicationClasspath()));
        assertFalse(apiClassText().contains(LAMBDA_METAFACTORY));
        assertTrue(Files.isRegularFile(lambdaClass()));
        assertTrue(lambdaClassText().contains(MARKER_DESCRIPTOR));

        BuildResult fallbackWarm = build(service, false);
        assertTrue(fallbackWarm.mainCompilationSkipped());

        BuildResult indy = build(service, true);
        assertFalse(indy.mainCompilationSkipped());
        assertEquals("annotated", invoke(artifacts.applicationClasspath()));
        assertTrue(apiClassText().contains(LAMBDA_METAFACTORY));
        assertTrue(apiClassText().contains(MARKER_DESCRIPTOR));
        assertFalse(Files.exists(lambdaClass()));

        BuildResult indyWarm = build(service, true);
        assertTrue(indyWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("annotated", invoke(artifacts.applicationClasspath()));
        assertFalse(apiClassText().contains(LAMBDA_METAFACTORY));
        assertTrue(Files.isRegularFile(lambdaClass()));
        assertTrue(lambdaClassText().contains(MARKER_DESCRIPTOR));
    }

    private BuildResult build(BuildService service, boolean annotatedIndy) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(annotatedIndy),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/AnnotatedLambdaApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @Target(AnnotationTarget.FUNCTION)
                @Retention(AnnotationRetention.RUNTIME)
                annotation class Marker

                object AnnotatedLambdaApi {
                    @JvmStatic
                    fun action(): () -> String = @Marker { "annotated" }
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
            Object action = Class.forName("com.example.AnnotatedLambdaApi", true, loader)
                    .getMethod("action")
                    .invoke(null);
            Class<?> function = Class.forName("kotlin.jvm.functions.Function0", true, loader);
            return (String) function.getMethod("invoke").invoke(action);
        }
    }

    private Path lambdaClass() {
        return projectDir.resolve(
                "target/classes/com/example/AnnotatedLambdaApi$action$1.class");
    }

    private String apiClassText() throws Exception {
        return classFileText(projectDir.resolve(
                "target/classes/com/example/AnnotatedLambdaApi.class"));
    }

    private String lambdaClassText() throws Exception {
        return classFileText(lambdaClass());
    }

    private static String classFileText(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
    }

    private static ProjectConfig config(boolean annotatedIndy) {
        String compilerArguments = annotatedIndy
                ? "\"-parameters\", \"-Xlambdas=indy\", \"-Xindy-allow-annotated-lambdas\""
                : "\"-parameters\", \"-Xlambdas=indy\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-annotated-indy-lambdas"
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
