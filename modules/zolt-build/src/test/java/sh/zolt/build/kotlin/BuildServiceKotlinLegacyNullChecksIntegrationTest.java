package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
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

/** Real compiler proof for Kotlin's legacy null-check compatibility mode. */
final class BuildServiceKotlinLegacyNullChecksIntegrationTest {
    private static final String MESSAGE =
            "Parameter specified as non-null is null: method com.example.NullCheckApi.length, parameter value";

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void restoresLegacyExceptionTypeAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertFailure(artifacts.applicationClasspath(), NullPointerException.class);
        assertClassFileContains("checkNotNullParameter");
        assertClassFileOmits("checkParameterIsNotNull");

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult legacy = build(service, true);
        assertFalse(legacy.mainCompilationSkipped());
        assertFailure(artifacts.applicationClasspath(), IllegalArgumentException.class);
        assertClassFileContains("checkParameterIsNotNull");
        assertClassFileOmits("checkNotNullParameter");

        BuildResult legacyWarm = build(service, true);
        assertTrue(legacyWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertFailure(artifacts.applicationClasspath(), NullPointerException.class);
        assertClassFileContains("checkNotNullParameter");
        assertClassFileOmits("checkParameterIsNotNull");
    }

    private BuildResult build(BuildService service, boolean legacyNullChecks) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(legacyNullChecks),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/NullCheckApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object NullCheckApi {
                    @JvmStatic
                    fun length(value: String): Int = value.length
                }
                """);
    }

    private void assertFailure(
            List<Path> applicationClasspath,
            Class<? extends Throwable> expectedType) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName("com.example.NullCheckApi", true, loader);
            try {
                type.getMethod("length", String.class).invoke(null, new Object[] {null});
                throw new AssertionError("Expected Kotlin parameter null check to fail");
            } catch (InvocationTargetException failure) {
                assertEquals(expectedType, failure.getCause().getClass());
                assertEquals(MESSAGE, failure.getCause().getMessage());
            }
        }
    }

    private void assertClassFileContains(String marker) throws Exception {
        assertTrue(classFileText().contains(marker), marker);
    }

    private void assertClassFileOmits(String marker) throws Exception {
        assertFalse(classFileText().contains(marker), marker);
    }

    private String classFileText() throws Exception {
        byte[] bytes = Files.readAllBytes(projectDir.resolve(
                "target/classes/com/example/NullCheckApi.class"));
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static ProjectConfig config(boolean legacyNullChecks) {
        String compilerArguments = legacyNullChecks
                ? "\"-parameters\", \"-Xno-unified-null-checks\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-legacy-null-checks"
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
