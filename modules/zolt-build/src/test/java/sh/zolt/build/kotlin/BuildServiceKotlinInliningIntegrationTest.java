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

/** Real compiler proof for Kotlin method-inlining control. */
final class BuildServiceKotlinInliningIntegrationTest {
    private static final int[] INLINED_BODY = {
        0x10, 0x15, 0x3b, 0x03, 0x3c, 0x1a, 0x05, 0x68, 0xac
    };
    private static final int[] STATIC_CALL = {0x10, 0x15, 0xb8, -1, -1, 0xac};

    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void disablesMethodInliningAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        byte[] baselineBytes = classFileBytes();
        assertTrue(contains(baselineBytes, INLINED_BODY));
        assertFalse(contains(baselineBytes, STATIC_CALL));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult disabled = build(service, true);
        assertFalse(disabled.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        byte[] disabledBytes = classFileBytes();
        assertFalse(Arrays.equals(baselineBytes, disabledBytes));
        assertFalse(contains(disabledBytes, INLINED_BODY));
        assertTrue(contains(disabledBytes, STATIC_CALL));

        BuildResult disabledWarm = build(service, true);
        assertTrue(disabledWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        assertArrayEquals(baselineBytes, classFileBytes());
    }

    private BuildResult build(BuildService service, boolean noInline) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(noInline),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/InlineApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @Suppress("NOTHING_TO_INLINE")
                inline fun doubled(value: Int): Int = value * 2

                object InlineApi {
                    @JvmStatic
                    fun answer(): Int = doubled(21)
                }
                """);
    }

    private int invoke(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            return (Integer) Class.forName("com.example.InlineApi", true, loader)
                    .getMethod("answer")
                    .invoke(null);
        }
    }

    private byte[] classFileBytes() throws Exception {
        return Files.readAllBytes(projectDir.resolve(
                "target/classes/com/example/InlineApi.class"));
    }

    private static boolean contains(byte[] bytes, int[] sequence) {
        for (int start = 0; start <= bytes.length - sequence.length; start++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                if (sequence[offset] >= 0 && (bytes[start + offset] & 0xff) != sequence[offset]) {
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

    private static ProjectConfig config(boolean noInline) {
        String compilerArguments = noInline
                ? "\"-parameters\", \"-Xno-inline\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-inlining"
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
