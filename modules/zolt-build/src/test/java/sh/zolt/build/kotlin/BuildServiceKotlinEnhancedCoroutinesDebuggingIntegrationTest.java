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

/** Real compiler proof for enhanced Kotlin coroutine debugging markers. */
final class BuildServiceKotlinEnhancedCoroutinesDebuggingIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void emitsCoroutineDebugMarkersAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, false);
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals("DEBUG", invoke(artifacts.applicationClasspath()));
        OutputBytes baselineBytes = outputBytes();
        assertFalse(contains(baselineBytes.file(), "$ecd$checkContinuation"));
        assertFalse(contains(baselineBytes.file(), "GeneratedCodeMarkers.kt"));

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult enhanced = build(service, true);
        assertFalse(enhanced.mainCompilationSkipped());
        assertEquals("DEBUG", invoke(artifacts.applicationClasspath()));
        OutputBytes enhancedBytes = outputBytes();
        assertFalse(Arrays.equals(baselineBytes.file(), enhancedBytes.file()));
        assertArrayEquals(baselineBytes.continuation(), enhancedBytes.continuation());
        assertTrue(contains(enhancedBytes.file(), "$ecd$checkContinuation"));
        assertTrue(contains(enhancedBytes.file(), "$ecd$tableswitch"));
        assertTrue(contains(enhancedBytes.file(), "$ecd$checkResult"));
        assertTrue(contains(enhancedBytes.file(), "$ecd$checkCOROUTINE_SUSPENDED"));
        assertTrue(contains(enhancedBytes.file(), "$ecd$unreachable"));
        assertTrue(contains(enhancedBytes.file(), "GeneratedCodeMarkers.kt"));

        BuildResult enhancedWarm = build(service, true);
        assertTrue(enhancedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("DEBUG", invoke(artifacts.applicationClasspath()));
        OutputBytes restoredBytes = outputBytes();
        assertArrayEquals(baselineBytes.file(), restoredBytes.file());
        assertArrayEquals(baselineBytes.continuation(), restoredBytes.continuation());
        assertFalse(contains(restoredBytes.file(), "$ecd$checkContinuation"));
        assertFalse(contains(restoredBytes.file(), "GeneratedCodeMarkers.kt"));
    }

    private BuildResult build(BuildService service, boolean enhancedDebugging) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(enhancedDebugging),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/CoroutineDebug.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                import kotlin.coroutines.Continuation
                import kotlin.coroutines.EmptyCoroutineContext
                import kotlin.coroutines.resume
                import kotlin.coroutines.startCoroutine
                import kotlin.coroutines.suspendCoroutine

                suspend fun marker(value: String): String = suspendCoroutine { continuation ->
                    continuation.resume(value)
                }

                suspend fun transformed(input: String): String {
                    val resumed = marker(input)
                    return resumed.uppercase()
                }

                object CoroutineDebugApi {
                    @JvmStatic
                    fun result(): String {
                        var outcome: Result<String>? = null
                        suspend { transformed("debug") }.startCoroutine(
                            object : Continuation<String> {
                                override val context = EmptyCoroutineContext

                                override fun resumeWith(result: Result<String>) {
                                    outcome = result
                                }
                            }
                        )
                        return checkNotNull(outcome).getOrThrow()
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
            return Class.forName("com.example.CoroutineDebugApi", true, loader)
                    .getMethod("result")
                    .invoke(null);
        }
    }

    private OutputBytes outputBytes() throws Exception {
        Path output = projectDir.resolve("target/classes/com/example");
        return new OutputBytes(
                Files.readAllBytes(output.resolve("CoroutineDebugKt.class")),
                Files.readAllBytes(output.resolve("CoroutineDebugKt$transformed$1.class")));
    }

    private static boolean contains(byte[] bytes, String value) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(value);
    }

    private static ProjectConfig config(boolean enhancedDebugging) {
        String compilerArguments = enhancedDebugging
                ? "\"-parameters\", \"-Xenhanced-coroutines-debugging\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-enhanced-coroutines-debugging"
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

    private record OutputBytes(byte[] file, byte[] continuation) {}
}
