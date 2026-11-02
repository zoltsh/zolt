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

/** Real compiler proof for package-specific Java nullability severity in mixed main sources. */
final class BuildServiceKotlinNullabilityAnnotationsIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void enforcesIndependentPackageRulesAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.prepare(
                cacheRoot,
                projectDir.resolve("zolt.lock"));
        sources();
        BuildService service = new BuildService();

        BuildResult ignored = build(service, "ignore", "ignore", false);
        assertFalse(ignored.mainCompilationSkipped());
        assertTrue(Files.isRegularFile(classFile("PackageNullnessApi.class")));

        BuildResult ignoredWarm = build(service, "ignore", "ignore", false);
        assertTrue(ignoredWarm.mainCompilationSkipped());

        KotlinCompileException jetBrainsWarning = assertThrows(
                KotlinCompileException.class,
                () -> build(service, "warn", "ignore", true));
        assertTrue(
                jetBrainsWarning.getMessage().contains("warnings found"),
                jetBrainsWarning.getMessage());
        assertTrue(
                jetBrainsWarning.getMessage().contains("JetBrainsApi.maybe"),
                jetBrainsWarning.getMessage());
        assertTrue(
                jetBrainsWarning.getMessage().contains("nullable receiver"),
                jetBrainsWarning.getMessage());

        KotlinCompileException androidStrict = assertThrows(
                KotlinCompileException.class,
                () -> build(service, "ignore", "strict", false));
        assertTrue(
                androidStrict.getMessage().contains("AndroidApi.maybe"),
                androidStrict.getMessage());
        assertTrue(
                androidStrict.getMessage().contains("nullable receiver"),
                androidStrict.getMessage());

        BuildResult restored = build(service, "ignore", "ignore", false);
        assertFalse(restored.mainCompilationSkipped());
        assertTrue(Files.isRegularFile(classFile("PackageNullnessApi.class")));

        BuildResult restoredWarm = build(service, "ignore", "ignore", false);
        assertTrue(restoredWarm.mainCompilationSkipped());
    }

    private BuildResult build(
            BuildService service,
            String jetBrainsMode,
            String androidMode,
            boolean warningsAsErrors) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(jetBrainsMode, androidMode, warningsAsErrors),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void sources() throws Exception {
        source("src/main/java/org/jetbrains/annotations/Nullable.java", """
                package org.jetbrains.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({
                    ElementType.METHOD,
                    ElementType.PARAMETER,
                    ElementType.FIELD,
                    ElementType.TYPE_USE
                })
                @Retention(RetentionPolicy.CLASS)
                public @interface Nullable {}
                """);
        source("src/main/java/com/android/annotations/Nullable.java", """
                package com.android.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({
                    ElementType.METHOD,
                    ElementType.PARAMETER,
                    ElementType.FIELD,
                    ElementType.TYPE_USE
                })
                @Retention(RetentionPolicy.CLASS)
                public @interface Nullable {}
                """);
        source("src/main/java/com/example/JetBrainsApi.java", """
                package com.example;

                import org.jetbrains.annotations.Nullable;

                public final class JetBrainsApi {
                    private JetBrainsApi() {}

                    @Nullable
                    public static String maybe() {
                        return null;
                    }
                }
                """);
        source("src/main/java/com/example/AndroidApi.java", """
                package com.example;

                import com.android.annotations.Nullable;

                public final class AndroidApi {
                    private AndroidApi() {}

                    @Nullable
                    public static String maybe() {
                        return null;
                    }
                }
                """);
        source("src/main/kotlin/com/example/PackageNullnessApi.kt", """
                package com.example

                object PackageNullnessApi {
                    @JvmStatic
                    fun unsafeJetBrainsLength(): Int = JetBrainsApi.maybe().length

                    @JvmStatic
                    fun unsafeAndroidLength(): Int = AndroidApi.maybe().length
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
            String jetBrainsMode,
            String androidMode,
            boolean warningsAsErrors) {
        String compilerArguments = ("\"-Xnullability-annotations=@org.jetbrains.annotations:%s\", "
                        + "\"-Xnullability-annotations=@com.android.annotations:%s\"").formatted(
                jetBrainsMode,
                androidMode);
        if (warningsAsErrors) {
            compilerArguments = "\"-Werror\", " + compilerArguments;
        }
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-package-nullness"
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
