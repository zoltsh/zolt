package sh.zolt.build.kotlin.compatibility.diagnostics;

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

/** Real-compiler proof for Kotlin 2.2 unused-return-value checking and reuse boundaries. */
final class BuildServiceKotlinReturnValueCheckerIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void checksAnnotatedScopesAndInvalidatesModeChanges() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        writeSource();
        BuildService service = new BuildService();

        BuildResult checked = build(service, "check");
        assertFalse(checked.mainCompilationSkipped());
        assertUnusedReturnWarning(checked.compilerOutput());
        assertEquals("return-value-checked", invoke(artifacts.applicationClasspath()));

        BuildResult checkedWarm = build(service, "check");
        assertTrue(checkedWarm.mainCompilationSkipped());

        BuildResult full = build(service, "full");
        assertFalse(full.mainCompilationSkipped());
        assertUnusedReturnWarning(full.compilerOutput());
        assertEquals("return-value-checked", invoke(artifacts.applicationClasspath()));

        BuildResult fullWarm = build(service, "full");
        assertTrue(fullWarm.mainCompilationSkipped());

        KotlinCompileException disabled = assertThrows(
                KotlinCompileException.class,
                () -> build(service, "disable"));
        assertTrue(diagnostics(disabled.getMessage()).contains(
                "ignorability-related annotations are experimental"), disabled.getMessage());
        assertTrue(
                diagnostics(disabled.getMessage()).contains("disabled state"),
                disabled.getMessage());

        BuildResult restored = build(service, "check");
        assertFalse(restored.mainCompilationSkipped());
        assertUnusedReturnWarning(restored.compilerOutput());
        assertEquals("return-value-checked", invoke(artifacts.applicationClasspath()));
    }

    private BuildResult build(BuildService service, String mode) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(mode),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void writeSource() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/ReturnValueApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @kotlin.MustUseReturnValue
                object ReturnValueApi {
                    @JvmStatic
                    fun answer(): Int = 42

                    @JvmStatic
                    fun message(): String {
                        answer()
                        return "return-value-checked"
                    }
                }
                """);
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
            return Class.forName("com.example.ReturnValueApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private static void assertUnusedReturnWarning(String output) {
        assertTrue(diagnostics(output).contains("unused return value"), output);
    }

    private static String diagnostics(String output) {
        return output.toLowerCase(Locale.ROOT);
    }

    private static ProjectConfig config(String mode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-return-value-checker"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = ["-Xreturn-value-checker=%s"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(mode));
    }
}
