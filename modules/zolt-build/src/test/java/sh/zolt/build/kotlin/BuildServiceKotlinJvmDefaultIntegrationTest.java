package sh.zolt.build.kotlin;

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

/** Real compiler proof for Kotlin JVM-default bytecode modes and stale-output cleanup. */
final class BuildServiceKotlinJvmDefaultIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void changesInterfaceBytecodeModeAndInvalidatesWarmCompilation() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source("""
                package com.example

                interface Greeting {
                    fun message(): String = "hello"
                }
                """);
        BuildService service = new BuildService();

        BuildResult disabled = build(service, "disable");
        assertFalse(disabled.mainCompilationSkipped());
        assertFalse(isDefaultMethod(artifacts.applicationClasspath()));
        assertTrue(Files.isRegularFile(defaultImpls()));

        BuildResult warm = build(service, "disable");
        assertTrue(warm.mainCompilationSkipped());

        BuildResult modern = build(service, "no-compatibility");
        assertFalse(modern.mainCompilationSkipped());
        assertTrue(isDefaultMethod(artifacts.applicationClasspath()));
        assertFalse(Files.exists(defaultImpls()));

        BuildResult restored = build(service, "enable");
        assertFalse(restored.mainCompilationSkipped());
        assertTrue(isDefaultMethod(artifacts.applicationClasspath()));
        assertTrue(Files.isRegularFile(defaultImpls()));
    }

    private BuildResult build(BuildService service, String mode) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(mode),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private boolean isDefaultMethod(List<Path> applicationClasspath) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(projectDir.resolve("target/classes").toUri().toURL());
        for (Path dependency : applicationClasspath) {
            urls.add(dependency.toUri().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(
                urls.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            return Class.forName("com.example.Greeting", true, loader)
                    .getDeclaredMethod("message")
                    .isDefault();
        }
    }

    private Path defaultImpls() {
        return projectDir.resolve("target/classes/com/example/Greeting$DefaultImpls.class");
    }

    private void source(String content) throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/Greeting.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private static ProjectConfig config(String mode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-jvm-default"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = ["-jvm-default=%s"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(mode));
    }
}
