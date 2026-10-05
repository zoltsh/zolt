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
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for rendering Kotlin diagnostic names. */
final class BuildServiceKotlinRenderInternalDiagnosticNamesIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void rendersWarningAndErrorNamesWithoutChangingSuccessfulOutput() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        writeSource(false);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals("named", invoke(artifacts.applicationClasspath()));
        String baselineDiagnostics = diagnostics(baseline.compilerOutput());
        assertTrue(baselineDiagnostics.contains("is deprecated. old api"));
        assertFalse(baselineDiagnostics.contains("[deprecation]"));
        OutputBytes baselineBytes = outputBytes();

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult named = build(service, true);
        assertFalse(named.mainCompilationSkipped());
        assertEquals("named", invoke(artifacts.applicationClasspath()));
        String namedDiagnostics = diagnostics(named.compilerOutput());
        assertTrue(namedDiagnostics.contains("[deprecation]"));
        assertTrue(namedDiagnostics.contains("is deprecated. old api"));
        OutputBytes namedBytes = outputBytes();
        assertArrayEquals(baselineBytes.api(), namedBytes.api());
        assertArrayEquals(baselineBytes.file(), namedBytes.file());

        BuildResult namedWarm = build(service, true);
        assertTrue(namedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertArrayEquals(baselineBytes.api(), outputBytes().api());
        assertArrayEquals(baselineBytes.file(), outputBytes().file());

        writeSource(true);
        KotlinCompileException ordinaryFailure = assertThrows(
                KotlinCompileException.class,
                () -> build(service, false));
        String ordinaryDiagnostics = diagnostics(ordinaryFailure.getMessage());
        assertTrue(ordinaryDiagnostics.contains("return type mismatch"));
        assertFalse(ordinaryDiagnostics.contains("[return_type_mismatch]"));

        KotlinCompileException namedFailure = assertThrows(
                KotlinCompileException.class,
                () -> build(service, true));
        String namedFailureDiagnostics = diagnostics(namedFailure.getMessage());
        assertTrue(namedFailureDiagnostics.contains("[return_type_mismatch]"));
        assertTrue(namedFailureDiagnostics.contains("return type mismatch"));
    }

    private BuildResult build(BuildService service, boolean renderDiagnosticNames) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(renderDiagnosticNames),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void writeSource(boolean broken) throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/DiagnosticNames.kt");
        Files.createDirectories(source.getParent());
        String brokenDeclaration = broken ? "fun broken(): Int = \"no\"" : "";
        Files.writeString(source, """
                package com.example

                @Deprecated("old API")
                fun oldApi() {}

                fun warningSite() {
                    oldApi()
                }

                object DiagnosticNamesApi {
                    @JvmStatic
                    fun message(): String = "named"
                }

                %s
                """.formatted(brokenDeclaration));
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
            return Class.forName("com.example.DiagnosticNamesApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private OutputBytes outputBytes() throws Exception {
        Path output = projectDir.resolve("target/classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("DiagnosticNamesApi.class")),
                Files.readAllBytes(output.resolve("DiagnosticNamesKt.class")));
    }

    private static String diagnostics(String output) {
        return output.toLowerCase(Locale.ROOT);
    }

    private static ProjectConfig config(boolean renderDiagnosticNames) {
        String compilerArguments = renderDiagnosticNames
                ? "\"-parameters\", \"-Xrender-internal-diagnostic-names\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-diagnostic-names"
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

    private record OutputBytes(byte[] api, byte[] file) {}
}
