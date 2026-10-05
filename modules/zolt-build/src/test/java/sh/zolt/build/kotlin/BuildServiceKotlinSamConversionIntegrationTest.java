package sh.zolt.build.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** Real compiler and output-cleanup proof for Kotlin/JVM SAM conversion. */
final class BuildServiceKotlinSamConversionIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void replacesSyntheticSamClassesWhenConversionModeChanges() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult classMode = build(service, "class");
        assertFalse(classMode.mainCompilationSkipped());
        assertEquals("sam-Zolt", invoke(artifacts.applicationClasspath()));
        assertFalse(samClasses().isEmpty(), "Class mode did not emit a synthetic SAM class");
        assertClassFileOmits("java/lang/invoke/LambdaMetafactory");

        BuildResult classWarm = build(service, "class");
        assertTrue(classWarm.mainCompilationSkipped());

        BuildResult indy = build(service, "indy");
        assertFalse(indy.mainCompilationSkipped());
        assertEquals("sam-Zolt", invoke(artifacts.applicationClasspath()));
        assertClassFileContains("java/lang/invoke/LambdaMetafactory");
        assertEquals(List.of(), samClasses(), "Indy rebuild left stale SAM classes");

        BuildResult indyWarm = build(service, "indy");
        assertTrue(indyWarm.mainCompilationSkipped());

        BuildResult restored = build(service, "class");
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("sam-Zolt", invoke(artifacts.applicationClasspath()));
        assertFalse(samClasses().isEmpty(), "Restored class mode did not emit a SAM class");
        assertClassFileOmits("java/lang/invoke/LambdaMetafactory");
    }

    private BuildResult build(BuildService service, String mode) {
        return service.buildWithClasspaths(
                        projectDir,
                        config(mode),
                        cacheRoot,
                        true)
                .buildResult();
    }

    private void source() throws Exception {
        Path source = projectDir.resolve("src/main/kotlin/com/example/SamApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                fun interface Transformer {
                    fun apply(value: String): String
                }

                object SamApi {
                    @JvmStatic
                    fun message(value: String): String {
                        val prefix = "sam-"
                        val transform = Transformer { input -> prefix + input }
                        return transform.apply(value)
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
            return Class.forName("com.example.SamApi", true, loader)
                    .getMethod("message", String.class)
                    .invoke(null, "Zolt");
        }
    }

    private List<String> samClasses() throws Exception {
        Path packageDirectory = projectDir.resolve("target/classes/com/example");
        try (var paths = Files.list(packageDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("SamApi$") && name.endsWith(".class"))
                    .sorted()
                    .toList();
        }
    }

    private void assertClassFileContains(String marker) throws Exception {
        assertTrue(classFileText().contains(marker), "Missing class-file marker `" + marker + "`");
    }

    private void assertClassFileOmits(String marker) throws Exception {
        assertFalse(classFileText().contains(marker), "Unexpected class-file marker `" + marker + "`");
    }

    private String classFileText() throws Exception {
        byte[] bytes = Files.readAllBytes(projectDir.resolve(
                "target/classes/com/example/SamApi.class"));
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static ProjectConfig config(String mode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-sam-conversion"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = ["-parameters", "-Xsam-conversions=%s"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(mode));
    }
}
