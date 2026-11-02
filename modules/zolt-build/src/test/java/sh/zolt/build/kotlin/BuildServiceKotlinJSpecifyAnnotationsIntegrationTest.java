package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for JSpecify nullness-severity modes in mixed main sources. */
final class BuildServiceKotlinJSpecifyAnnotationsIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void enforcesSeverityAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.prepare(
                cacheRoot,
                projectDir.resolve("zolt.lock"));
        sources();
        BuildService service = new BuildService();

        BuildResult ignored = build(service, "ignore", false);
        assertFalse(ignored.mainCompilationSkipped());
        assertTrue(Files.isRegularFile(classFile("JSpecifyApi.class")));

        BuildResult ignoredWarm = build(service, "ignore", false);
        assertTrue(ignoredWarm.mainCompilationSkipped());

        KotlinCompileException warned = assertThrows(
                KotlinCompileException.class,
                () -> build(service, "warn", true));
        assertTrue(warned.getMessage().contains("warnings found"), warned.getMessage());
        assertTrue(warned.getMessage().contains("nullable receiver"), warned.getMessage());

        KotlinCompileException strict = assertThrows(
                KotlinCompileException.class,
                () -> build(service, "strict", false));
        assertTrue(strict.getMessage().contains("nullable receiver"), strict.getMessage());

        BuildResult restored = build(service, "ignore", false);
        assertFalse(restored.mainCompilationSkipped());
        assertTrue(Files.isRegularFile(classFile("JSpecifyApi.class")));

        BuildResult restoredWarm = build(service, "ignore", false);
        assertTrue(restoredWarm.mainCompilationSkipped());
    }

    private BuildResult build(
            BuildService service,
            String jspecifyMode,
            boolean warningsAsErrors) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(jspecifyMode, warningsAsErrors),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void sources() throws Exception {
        source("src/main/java/org/jspecify/annotations/Nullable.java", """
                package org.jspecify.annotations;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Documented
                @Target(ElementType.TYPE_USE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Nullable {}
                """);
        source("src/main/java/com/example/JavaApi.java", """
                package com.example;

                import org.jspecify.annotations.Nullable;

                public final class JavaApi {
                    private JavaApi() {}

                    public static @Nullable String maybe() {
                        return null;
                    }
                }
                """);
        source("src/main/kotlin/com/example/JSpecifyApi.kt", """
                package com.example

                object JSpecifyApi {
                    @JvmStatic
                    fun unsafeLength(): Int = JavaApi.maybe().length
                }
                """);
    }

    private void source(String relativePath, String content) throws Exception {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private Path classFile(String name) {
        return projectDir.resolve("target/classes/com/example").resolve(name);
    }

    private static ProjectConfig config(
            String jspecifyMode,
            boolean warningsAsErrors) {
        String compilerArguments = warningsAsErrors
                ? "\"-Werror\", \"-Xjspecify-annotations=%s\"".formatted(jspecifyMode)
                : "\"-Xjspecify-annotations=%s\"".formatted(jspecifyMode);
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-jspecify-annotations"
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
