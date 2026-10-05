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

/** Real compiler proof for Kotlin/JVM string-concatenation code generation. */
final class BuildServiceKotlinStringConcatIntegrationTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path cacheRoot;

    @Test
    void changesBytecodeSchemeAndInvalidatesReuseWithTheConfiguredMode() throws Exception {
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KotlinCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDir.resolve("zolt.lock"));
        source();
        BuildService service = new BuildService();

        BuildResult inline = build(service, "inline");
        assertFalse(inline.mainCompilationSkipped());
        assertEquals("hello Zolt 7", invoke(artifacts.applicationClasspath()));
        assertClassFileContains("java/lang/StringBuilder");
        assertClassFileOmits("java/lang/invoke/StringConcatFactory");

        BuildResult inlineWarm = build(service, "inline");
        assertTrue(inlineWarm.mainCompilationSkipped());

        BuildResult indy = build(service, "indy");
        assertFalse(indy.mainCompilationSkipped());
        assertEquals("hello Zolt 7", invoke(artifacts.applicationClasspath()));
        assertClassFileContains("java/lang/invoke/StringConcatFactory");
        assertClassFileContains("makeConcat");
        assertClassFileOmits("makeConcatWithConstants");

        BuildResult indyWarm = build(service, "indy");
        assertTrue(indyWarm.mainCompilationSkipped());

        BuildResult indyWithConstants = build(service, "indy-with-constants");
        assertFalse(indyWithConstants.mainCompilationSkipped());
        assertEquals("hello Zolt 7", invoke(artifacts.applicationClasspath()));
        assertClassFileContains("java/lang/invoke/StringConcatFactory");
        assertClassFileContains("makeConcatWithConstants");

        BuildResult restored = build(service, "inline");
        assertFalse(restored.mainCompilationSkipped());
        assertEquals("hello Zolt 7", invoke(artifacts.applicationClasspath()));
        assertClassFileContains("java/lang/StringBuilder");
        assertClassFileOmits("java/lang/invoke/StringConcatFactory");
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
        Path source = projectDir.resolve("src/main/kotlin/com/example/StringConcatApi.kt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example

                object StringConcatApi {
                    @JvmStatic
                    fun message(name: String, count: Int): String = "hello " + name + " " + count
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
            return Class.forName("com.example.StringConcatApi", true, loader)
                    .getMethod("message", String.class, int.class)
                    .invoke(null, "Zolt", 7);
        }
    }

    private void assertClassFileContains(String marker) throws Exception {
        String classFile = classFileText();
        assertTrue(classFile.contains(marker), "Missing class-file marker `" + marker + "`");
    }

    private void assertClassFileOmits(String marker) throws Exception {
        String classFile = classFileText();
        assertFalse(classFile.contains(marker), "Unexpected class-file marker `" + marker + "`");
    }

    private String classFileText() throws Exception {
        byte[] bytes = Files.readAllBytes(projectDir.resolve(
                "target/classes/com/example/StringConcatApi.class"));
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static ProjectConfig config(String mode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "kotlin-string-concat"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [compiler]
                args = ["-parameters", "-Xstring-concat=%s"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"
                """.formatted(mode));
    }
}
