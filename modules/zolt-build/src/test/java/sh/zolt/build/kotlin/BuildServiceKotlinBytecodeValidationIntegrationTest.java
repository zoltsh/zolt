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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.KotlinCompilerIntegrationArtifacts;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real compiler proof for Kotlin generated-bytecode validation. */
final class BuildServiceKotlinBytecodeValidationIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void validatesGeneratedBytecodeAndInvalidatesWarmCompilation() throws Exception {
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

        BuildResult baselineWarm = build(service, false);
        assertTrue(baselineWarm.mainCompilationSkipped());

        BuildResult validated = build(service, true);
        assertFalse(validated.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        assertArrayEquals(baselineBytes, classFileBytes());

        BuildResult validatedWarm = build(service, true);
        assertTrue(validatedWarm.mainCompilationSkipped());

        BuildResult restored = build(service, false);
        assertFalse(restored.mainCompilationSkipped());
        assertEquals(42, invoke(artifacts.applicationClasspath()));
        assertArrayEquals(baselineBytes, classFileBytes());
    }

    private BuildResult build(BuildService service, boolean validateBytecode) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(validateBytecode),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/ValidatedApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object ValidatedApi {
                    @JvmStatic
                    fun answer(): Int = (1..6).sum() * 2
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
            return (Integer) Class.forName("com.example.ValidatedApi", true, loader)
                    .getMethod("answer")
                    .invoke(null);
        }
    }

    private byte[] classFileBytes() throws Exception {
        return Files.readAllBytes(projectDir.resolve(
                "target/classes/com/example/ValidatedApi.class"));
    }

    private static ProjectConfig config(boolean validateBytecode) {
        String compilerArguments = validateBytecode
                ? "\"-parameters\", \"-Xvalidate-bytecode\""
                : "\"-parameters\"";
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-bytecode-validation"
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
