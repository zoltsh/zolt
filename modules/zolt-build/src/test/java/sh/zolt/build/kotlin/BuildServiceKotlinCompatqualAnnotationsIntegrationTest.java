package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for Checker Framework compatqual handling in mixed main sources. */
final class BuildServiceKotlinCompatqualAnnotationsIntegrationTest {
    private static final String OPTION =
            "-Xsupport-compatqual-checker-framework-annotations=";

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void controlsCompatqualNullnessAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        sources();
        BuildService service = new BuildService();

        BuildResult disabled = build(service, "disable");
        assertFalse(disabled.mainCompilationSkipped());
        assertEquals(6, invoke(artifacts.applicationClasspath()));
        byte[] disabledBytes = Files.readAllBytes(classFile("CompatqualApi.class"));

        BuildResult disabledWarm = build(service, "disable");
        assertTrue(disabledWarm.mainCompilationSkipped());

        KotlinCompileException defaultMode = assertThrows(
                KotlinCompileException.class,
                () -> build(service, ""));
        assertTrue(defaultMode.getMessage().contains("nullable receiver"), defaultMode.getMessage());

        KotlinCompileException enabled = assertThrows(
                KotlinCompileException.class,
                () -> build(service, "enable"));
        assertTrue(enabled.getMessage().contains("nullable receiver"), enabled.getMessage());

        BuildResult restored = build(service, "disable");
        assertFalse(restored.mainCompilationSkipped());
        assertEquals(6, invoke(artifacts.applicationClasspath()));
        assertArrayEquals(disabledBytes, Files.readAllBytes(classFile("CompatqualApi.class")));

        BuildResult restoredWarm = build(service, "disable");
        assertTrue(restoredWarm.mainCompilationSkipped());
    }

    private BuildResult build(BuildService service, String compatqualMode) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(compatqualMode),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void sources() throws Exception {
        source(
                "src/main/java/org/checkerframework/checker/nullness/compatqual/NullableDecl.java",
                """
                package org.checkerframework.checker.nullness.compatqual;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Documented
                @Target({
                    ElementType.TYPE_USE,
                    ElementType.METHOD,
                    ElementType.PARAMETER,
                    ElementType.FIELD
                })
                @Retention(RetentionPolicy.RUNTIME)
                public @interface NullableDecl {}
                """);
        source("src/main/java/com/example/LegacyApi.java", """
                package com.example;

                import org.checkerframework.checker.nullness.compatqual.NullableDecl;

                public final class LegacyApi {
                    private LegacyApi() {}

                    @NullableDecl
                    public static String value() {
                        return "compat";
                    }
                }
                """);
        source("src/main/kotlin/com/example/CompatqualApi.kt", """
                package com.example

                object CompatqualApi {
                    @JvmStatic
                    fun length(): Int = LegacyApi.value().length
                }
                """);
    }

    private void source(String relativePath, String content) throws Exception {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private Object invoke(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            return Class.forName("com.example.CompatqualApi", true, loader)
                    .getMethod("length")
                    .invoke(null);
        }
    }

    private Path classFile(String name) {
        return projectDir.resolve("target/classes/com/example").resolve(name);
    }

    private static ProjectConfig config(String compatqualMode) {
        String compilerArguments = compatqualMode.isEmpty()
                ? "\"-parameters\""
                : "\"-parameters\", \"%s%s\"".formatted(OPTION, compatqualMode);
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-compatqual-annotations"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/java", "src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = [%s]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(compilerArguments));
    }
}
