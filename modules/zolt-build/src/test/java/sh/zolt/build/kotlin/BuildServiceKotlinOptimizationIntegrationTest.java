package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Real compiler proof for Kotlin backend-optimization control. */
final class BuildServiceKotlinOptimizationIntegrationTest {
    private static final int[] COMPACT_BRANCH = {0x1a, 0x04, 0x60, 0x3c, 0x1b, 0x9e};
    private static final int[] EXPLICIT_ZERO_BRANCH = {0x1a, 0x04, 0x60, 0x3c, 0x1b, 0x03, 0xa4};

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void disablesBackendOptimizationAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult optimized = build(service, false);
        assertFalse(optimized.mainCompilationSkipped());
        assertBehavior(artifacts.applicationClasspath());
        byte[] optimizedBytes = classFileBytes();
        assertTrue(contains(optimizedBytes, COMPACT_BRANCH));
        assertFalse(contains(optimizedBytes, EXPLICIT_ZERO_BRANCH));

        BuildResult optimizedWarm = build(service, false);
        assertTrue(optimizedWarm.mainCompilationSkipped());

        BuildResult unoptimized = build(service, true);
        assertFalse(unoptimized.mainCompilationSkipped());
        assertBehavior(artifacts.applicationClasspath());
        byte[] unoptimizedBytes = classFileBytes();
        assertFalse(Arrays.equals(optimizedBytes, unoptimizedBytes));
        assertTrue(contains(unoptimizedBytes, EXPLICIT_ZERO_BRANCH));
        assertFalse(contains(unoptimizedBytes, COMPACT_BRANCH));

        BuildResult unoptimizedWarm = build(service, true);
        assertTrue(unoptimizedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertBehavior(artifacts.applicationClasspath());
        assertArrayEquals(optimizedBytes, classFileBytes());
    }

    private BuildResult build(BuildService service, boolean noOptimize) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(noOptimize),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/OptimizeApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object OptimizeApi {
                    @JvmStatic
                    fun classify(value: Int): String {
                        val adjusted = value + 1
                        return if (adjusted > 0) "positive" else "non-positive"
                    }
                }
                """);
    }

    private void assertBehavior(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            var classify = Class.forName("com.example.OptimizeApi", true, loader)
                    .getMethod("classify", int.class);
            assertEquals("non-positive", classify.invoke(null, -2));
            assertEquals("positive", classify.invoke(null, 0));
        }
    }

    private byte[] classFileBytes() throws Exception {
        return Files.readAllBytes(projectDir.resolve(
                "target/classes/com/example/OptimizeApi.class"));
    }

    private static boolean contains(byte[] bytes, int[] sequence) {
        for (int start = 0; start <= bytes.length - sequence.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                if ((bytes[start + offset] & 0xff) != sequence[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static ProjectConfig config(boolean noOptimize) {
        String compilerArguments = noOptimize
                ? "\"-parameters\", \"-Xno-optimize\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-optimization"
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
