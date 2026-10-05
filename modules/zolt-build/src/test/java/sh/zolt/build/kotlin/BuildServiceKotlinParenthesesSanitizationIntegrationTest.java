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
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for sanitizing parentheses in Kotlin JVM method names. */
final class BuildServiceKotlinParenthesesSanitizationIntegrationTest {
    private static final String SOURCE_NAME = "call(me)";
    private static final String SANITIZED_NAME = "call$_me$_";

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void sanitizesParenthesesAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertMethodName(artifacts.applicationClasspath(), SOURCE_NAME, SANITIZED_NAME);
        byte[] baselineBytes = classFileBytes();

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult sanitized = build(service, true);
        assertFalse(sanitized.mainCompilationSkipped());
        assertMethodName(artifacts.applicationClasspath(), SANITIZED_NAME, SOURCE_NAME);
        assertFalse(Arrays.equals(baselineBytes, classFileBytes()));

        BuildResult sanitizedWarm = build(service, true);
        assertTrue(sanitizedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertMethodName(artifacts.applicationClasspath(), SOURCE_NAME, SANITIZED_NAME);
        assertArrayEquals(baselineBytes, classFileBytes());
    }

    private BuildResult build(BuildService service, boolean sanitizeParentheses) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(sanitizeParentheses),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/ParenthesesApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object ParenthesesApi {
                    @JvmStatic
                    fun `call(me)`(): String = "sanitized"
                }
                """);
    }

    private void assertMethodName(
            List<Path> applicationClasspath,
            String expected,
            String absent) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName("com.example.ParenthesesApi", true, loader);
            assertEquals("sanitized", type.getMethod(expected).invoke(null));
            assertThrows(NoSuchMethodException.class, () -> type.getMethod(absent));
        }
    }

    private byte[] classFileBytes() throws Exception {
        return Files.readAllBytes(projectDir.resolve(
                "target/classes/com/example/ParenthesesApi.class"));
    }

    private static ProjectConfig config(boolean sanitizeParentheses) {
        String compilerArguments = sanitizeParentheses
                ? "\"-parameters\", \"-Xsanitize-parentheses\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-parentheses-sanitization"
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
