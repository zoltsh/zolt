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

/** Real compiler proof for global JSR-305 severity in mixed main sources. */
final class BuildServiceKotlinJsr305IntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void enforcesCustomDefaultSeverityAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.prepare(
                cacheRoot,
                projectDir.resolve("zolt.lock"));
        sources();
        BuildService service = new BuildService();

        BuildResult ignored = build(service, "ignore", false);
        assertFalse(ignored.mainCompilationSkipped());
        assertTrue(Files.isRegularFile(classFile("Jsr305Api.class")));

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
        assertTrue(Files.isRegularFile(classFile("Jsr305Api.class")));

        BuildResult restoredWarm = build(service, "ignore", false);
        assertTrue(restoredWarm.mainCompilationSkipped());
    }

    private BuildResult build(
            BuildService service,
            String jsr305Mode,
            boolean warningsAsErrors) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(jsr305Mode, warningsAsErrors),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void sources() throws Exception {
        source("src/main/java/javax/annotation/meta/When.java", """
                package javax.annotation.meta;

                public enum When {
                    ALWAYS,
                    UNKNOWN,
                    MAYBE,
                    NEVER
                }
                """);
        source("src/main/java/javax/annotation/meta/TypeQualifier.java", """
                package javax.annotation.meta;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Documented
                @Target(ElementType.ANNOTATION_TYPE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface TypeQualifier {}
                """);
        source("src/main/java/javax/annotation/meta/TypeQualifierDefault.java", """
                package javax.annotation.meta;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Documented
                @Target(ElementType.ANNOTATION_TYPE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface TypeQualifierDefault {
                    ElementType[] value();
                }
                """);
        source("src/main/java/javax/annotation/Nonnull.java", """
                package javax.annotation;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                import javax.annotation.meta.TypeQualifier;
                import javax.annotation.meta.When;

                @Documented
                @TypeQualifier
                @Target({
                    ElementType.METHOD,
                    ElementType.FIELD,
                    ElementType.ANNOTATION_TYPE,
                    ElementType.CONSTRUCTOR,
                    ElementType.PARAMETER
                })
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Nonnull {
                    When when() default When.ALWAYS;
                }
                """);
        source("src/main/java/com/example/NullableApi.java", """
                package com.example;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                import javax.annotation.Nonnull;
                import javax.annotation.meta.TypeQualifierDefault;
                import javax.annotation.meta.When;

                @Documented
                @Nonnull(when = When.MAYBE)
                @TypeQualifierDefault({
                    ElementType.METHOD,
                    ElementType.PARAMETER,
                    ElementType.TYPE_USE
                })
                @Target({ElementType.TYPE, ElementType.PACKAGE})
                @Retention(RetentionPolicy.RUNTIME)
                public @interface NullableApi {}
                """);
        source("src/main/java/com/example/JavaApi.java", """
                package com.example;

                @NullableApi
                public final class JavaApi {
                    private JavaApi() {}

                    public static String maybe() {
                        return null;
                    }
                }
                """);
        source("src/main/kotlin/com/example/Jsr305Api.kt", """
                package com.example

                object Jsr305Api {
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
            String jsr305Mode,
            boolean warningsAsErrors) {
        String compilerArguments = warningsAsErrors
                ? "\"-Werror\", \"-Xjsr305=%s\"".formatted(jsr305Mode)
                : "\"-Xjsr305=%s\"".formatted(jsr305Mode);
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-jsr305"
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
