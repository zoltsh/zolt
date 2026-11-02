package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
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

/** Real compiler proof for numbered Kotlin inline-scope debug markers. */
final class BuildServiceKotlinInlineScopeNumbersIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void numbersInlineScopeMarkersAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        OutputBytes baselineBytes = outputBytes();
        assertTrue(contains(baselineBytes.api(), "$i$f$doubled"));
        assertTrue(contains(baselineBytes.api(), "value$iv"));
        assertFalse(contains(baselineBytes.api(), "$i$f$doubled\\"));
        assertFalse(contains(baselineBytes.api(), "value\\"));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult numbered = build(service, true);
        assertFalse(numbered.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        OutputBytes numberedBytes = outputBytes();
        assertFalse(Arrays.equals(baselineBytes.api(), numberedBytes.api()));
        assertArrayEquals(baselineBytes.file(), numberedBytes.file());
        assertTrue(contains(numberedBytes.api(), "$i$f$doubled\\"));
        assertTrue(contains(numberedBytes.api(), "value\\"));
        assertFalse(contains(numberedBytes.api(), "value$iv"));

        BuildResult numberedWarm = build(service, true);
        assertTrue(numberedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        OutputBytes restoredBytes = outputBytes();
        assertArrayEquals(baselineBytes.api(), restoredBytes.api());
        assertArrayEquals(baselineBytes.file(), restoredBytes.file());
    }

    private BuildResult build(BuildService service, boolean useInlineScopeNumbers) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(useInlineScopeNumbers),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/InlineScopes.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                @Suppress("NOTHING_TO_INLINE")
                inline fun doubled(value: Int): Int = value * 2

                object InlineScopesApi {
                    @JvmStatic
                    fun answer(): Int = doubled(21)
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
            return Class.forName("com.example.InlineScopesApi", true, loader)
                    .getMethod("answer")
                    .invoke(null);
        }
    }

    private OutputBytes outputBytes() throws Exception {
        Path output = projectDir.resolve("target/classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("InlineScopesApi.class")),
                Files.readAllBytes(output.resolve("InlineScopesKt.class")));
    }

    private static boolean contains(byte[] bytes, String value) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(value);
    }

    private static ProjectConfig config(boolean useInlineScopeNumbers) {
        String compilerArguments = useInlineScopeNumbers
                ? "\"-parameters\", \"-Xuse-inline-scopes-numbers\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-inline-scope-numbers"
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
