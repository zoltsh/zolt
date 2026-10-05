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

/** Real compiler proof for complete Kotlin warning reporting after errors. */
final class BuildServiceKotlinReportAllWarningsIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void reportsWarningsAlongsideErrorsAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        writeSource(false);
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertTrue(diagnostics(baseline.compilerOutput()).contains("is deprecated. old api"));
        assertEquals("complete", invoke(artifacts.applicationClasspath()));
        OutputBytes baselineBytes = outputBytes();

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult enabled = build(service, true);
        assertFalse(enabled.mainCompilationSkipped());
        assertTrue(diagnostics(enabled.compilerOutput()).contains("is deprecated. old api"));
        assertEquals("complete", invoke(artifacts.applicationClasspath()));
        OutputBytes enabledBytes = outputBytes();
        assertArrayEquals(baselineBytes.api(), enabledBytes.api());
        assertArrayEquals(baselineBytes.file(), enabledBytes.file());

        BuildResult enabledWarm = build(service, true);
        assertTrue(enabledWarm.mainCompilationSkipped());

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
        assertFalse(ordinaryDiagnostics.contains("is deprecated. old api"));

        KotlinCompileException completeFailure = assertThrows(
                KotlinCompileException.class,
                () -> build(service, true));
        String completeDiagnostics = diagnostics(completeFailure.getMessage());
        assertTrue(completeDiagnostics.contains("return type mismatch"));
        assertTrue(completeDiagnostics.contains("is deprecated. old api"));
    }

    private BuildResult build(BuildService service, boolean reportAllWarnings) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(reportAllWarnings),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void writeSource(boolean broken) throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/WarningReport.kt");
        Files.createDirectories(source.getParent());
        String brokenDeclaration = broken ? "fun broken(): String = 42" : "";
        Files.writeString(source, """
                package com.example

                @Deprecated("old API")
                fun oldApi() {}

                fun warningSite() {
                    oldApi()
                }

                object WarningReportApi {
                    @JvmStatic
                    fun message(): String = "complete"
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
            return Class.forName("com.example.WarningReportApi", true, loader)
                    .getMethod("message")
                    .invoke(null);
        }
    }

    private OutputBytes outputBytes() throws Exception {
        Path output = projectDir.resolve("target/classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("WarningReportApi.class")),
                Files.readAllBytes(output.resolve("WarningReportKt.class")));
    }

    private static String diagnostics(String output) {
        return output.toLowerCase(Locale.ROOT);
    }

    private static ProjectConfig config(boolean reportAllWarnings) {
        String compilerArguments = reportAllWarnings
                ? "\"-parameters\", \"-Xreport-all-warnings\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-complete-warning-reports"
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
