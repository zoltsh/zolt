package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/** Real KSP2 process, generated-lane, compilation, resource, and warm-reuse proof. */
final class BuildServiceKspIntegrationTest {
    @TempDir
    private Path projectDirectory;

    @TempDir
    private Path cacheRoot;

    @Test
    void generatesAndCompilesKotlinJavaAndResourcesOffline() throws Exception {
        Path processor = KspProcessorFixture.processorJar(
                projectDirectory.resolve("fixture/ksp-fixture-processor.jar"));
        KotlinCompilerIntegrationArtifacts.Prepared artifacts =
                KspCompilerIntegrationArtifacts.prepare(
                        cacheRoot,
                        projectDirectory.resolve("zolt.lock"),
                        processor);
        source("src/main/kotlin/com/example/Application.kt", """
                package com.example

                object Application {
                    @JvmStatic
                    fun value(): String =
                        GeneratedKspMessage.value() + "-" + GeneratedJavaMessage.value()
                }
                """);
        ProjectConfig config = config();
        BuildService service = new BuildService();

        BuildResultWithClasspaths first = service.buildWithClasspaths(
                projectDirectory, config, cacheRoot, true);

        Path output = projectDirectory.resolve("target/generated/ksp/main/symbols");
        assertTrue(first.buildResult().resolveResult().isEmpty());
        assertEquals("full", first.buildResult().mainCompilationMode());
        assertTrue(Files.isRegularFile(output.resolve("kotlin/com/example/GeneratedKspMessage.kt")));
        assertTrue(Files.isRegularFile(output.resolve("java/com/example/GeneratedJavaMessage.java")));
        assertEquals(
                "from-ksp-resource\n",
                Files.readString(output.resolve("resources/META-INF/ksp-fixture.txt")));
        assertEquals(
                "from-ksp-resource\n",
                Files.readString(projectDirectory.resolve(
                        "target/classes/META-INF/ksp-fixture.txt")));
        assertTrue(Files.isRegularFile(classFile("GeneratedKspMessage.class")));
        assertTrue(Files.isRegularFile(classFile("GeneratedJavaMessage.class")));
        assertEquals("from-ksp-from-ksp", invoke(artifacts.applicationClasspath()));
        assertTrue(first.classpathPackages().stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_EXEC)
                .anyMatch(dependency -> dependency.toolGroups().contains("ksp:ksp:engine")));
        assertFalse(first.classpaths().compile().entries().contains(processor));

        BuildResultWithClasspaths warm = service.buildWithClasspaths(
                projectDirectory, config, cacheRoot, true);

        assertTrue(warm.buildResult().mainCompilationSkipped());
        assertEquals("from-ksp-from-ksp", invoke(artifacts.applicationClasspath()));
    }

    private ProjectConfig config() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "ksp-integration"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [build]
                sources = ["src/main/kotlin"]

                [toolchain.kotlin]
                version = "2.2.0"

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "2.2.0"

                [generated.tools.ksp]
                version = "2.2.0-2.0.2"
                coordinates = [
                    { coordinate = "com.example:ksp-fixture-processor", version = "1.0.0" },
                ]

                [generated.main.symbols]
                kind = "ksp"
                options = { "fixture.message" = "from-ksp" }
                """);
    }

    private void source(String relative, String content) throws Exception {
        Path path = projectDirectory.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private Path classFile(String name) {
        return projectDirectory.resolve("target/classes/com/example").resolve(name);
    }

    private String invoke(List<Path> applicationClasspath) throws Exception {
        URL[] urls = Stream.concat(
                        Stream.of(projectDirectory.resolve("target/classes")),
                        applicationClasspath.stream())
                .map(BuildServiceKspIntegrationTest::url)
                .toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            return (String) loader.loadClass("com.example.Application")
                    .getMethod("value")
                    .invoke(null);
        }
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (java.net.MalformedURLException exception) {
            throw new IllegalArgumentException(exception);
        }
    }
}
