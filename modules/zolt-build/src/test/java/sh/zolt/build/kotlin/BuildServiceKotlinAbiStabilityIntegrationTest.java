package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
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

/** Real compiler proof for stable and unstable Kotlin ABI metadata markers. */
final class BuildServiceKotlinAbiStabilityIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void marksAbiStabilityAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult baseline = build(service, "");
        assertFalse(baseline.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        assertEquals(48, metadataExtraInt(artifacts.applicationClasspath()));
        byte[] baselineBytes = outputBytes();

        BuildResult baselineWarm = build(service, "");
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult stable = build(service, "stable");
        assertFalse(stable.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        assertEquals(48, metadataExtraInt(artifacts.applicationClasspath()));
        assertArrayEquals(baselineBytes, outputBytes());

        BuildResult stableWarm = build(service, "stable");
        assertTrue(stableWarm.mainCompilationSkipped());

        BuildResult unstable = build(service, "unstable");
        assertFalse(unstable.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        assertEquals(16, metadataExtraInt(artifacts.applicationClasspath()));
        assertFalse(Arrays.equals(baselineBytes, outputBytes()));

        BuildResult unstableWarm = build(service, "unstable");
        assertTrue(unstableWarm.mainCompilationSkipped());

        BuildResult restored = build(service, "");
        assertFalse(restored.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        assertEquals(48, metadataExtraInt(artifacts.applicationClasspath()));
        assertArrayEquals(baselineBytes, outputBytes());
    }

    private BuildResult build(BuildService service, String abiStability) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(abiStability),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/AbiLibrary.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object AbiLibrary {
                    @JvmStatic
                    fun answer(): Int = 42
                }
                """);
    }

    private Object invoke(List<Path> applicationClasspath) throws Exception {
        try (URLClassLoader loader = loader(applicationClasspath)) {
            return Class.forName("com.example.AbiLibrary", true, loader)
                    .getMethod("answer")
                    .invoke(null);
        }
    }

    private int metadataExtraInt(List<Path> applicationClasspath) throws Exception {
        try (URLClassLoader loader = loader(applicationClasspath)) {
            Class<?> type = Class.forName("com.example.AbiLibrary", true, loader);
            Annotation metadata = Arrays.stream(type.getAnnotations())
                    .filter(annotation -> annotation.annotationType()
                            .getName()
                            .equals("kotlin.Metadata"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing kotlin.Metadata annotation"));
            return (int) metadata.annotationType().getMethod("xi").invoke(metadata);
        }
    }

    private URLClassLoader loader(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        return new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader());
    }

    private byte[] outputBytes() throws Exception {
        return Files.readAllBytes(
                projectDir.resolve("target/classes/com/example/AbiLibrary.class"));
    }

    private static ProjectConfig config(String abiStability) {
        String compilerArguments = abiStability.isEmpty()
                ? "\"-parameters\""
                : "\"-parameters\", \"-Xabi-stability=%s\"".formatted(abiStability);
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-abi-stability"
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
