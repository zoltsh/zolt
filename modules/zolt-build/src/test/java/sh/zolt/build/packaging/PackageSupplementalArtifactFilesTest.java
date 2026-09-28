package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageSupplementalArtifactFilesTest {
    @TempDir
    private Path tempDir;

    @Test
    void sourceArchiveFilesIncludeJavaAndGroovyInEntryOrder() throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        write(sourceRoot.resolve("com/example/Beta.java"));
        write(sourceRoot.resolve("com/example/Alpha.java"));
        write(sourceRoot.resolve("com/example/GroovyApi.groovy"));
        write(sourceRoot.resolve("com/example/application.properties"));

        List<Path> files = PackageSupplementalArtifactFiles.sourceArchiveFiles(sourceRoot);

        assertEquals(List.of(
                sourceRoot.resolve("com/example/Alpha.java"),
                sourceRoot.resolve("com/example/Beta.java"),
                sourceRoot.resolve("com/example/GroovyApi.groovy")), files);
    }

    @Test
    void javadocSourceFilesIncludeOnlyJava() throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java");
        write(sourceRoot.resolve("com/example/App.java"));
        write(sourceRoot.resolve("com/example/GroovyApi.groovy"));

        assertEquals(
                List.of(sourceRoot.resolve("com/example/App.java")),
                PackageSupplementalArtifactFiles.javadocSourceFiles(sourceRoot));
    }

    @Test
    void sourceJarIncludesGroovyWhileJavadocConsumesOnlyJava() throws IOException {
        Path sourceRoot = tempDir.resolve("src/main/java/com/example");
        write(sourceRoot.resolve("App.java"), """
                package com.example;

                /** Application API. */
                public final class App {
                }
                """);
        write(sourceRoot.resolve("GroovyApi.groovy"), """
                package com.example
                class GroovyApi {
                    def answer() { 42 }
                }
                """);
        Path classes = tempDir.resolve("target/classes");
        Files.createDirectories(classes);
        PackageSettings packageSettings = new PackageSettings(
                PackageMode.THIN,
                true,
                true,
                false,
                null);

        List<PackageArtifact> artifacts = new PackageSupplementalArtifactAssembler(new ClasspathBuilder())
                .assemble(
                        tempDir,
                        config(Optional.empty()).withPackageSettings(packageSettings),
                        new BuildResult(Optional.empty(), 2, 0, classes, ""),
                        Optional.empty(),
                        Optional.empty());

        assertEquals(List.of("sources", "javadoc"), artifacts.stream()
                .map(PackageArtifact::classifier)
                .toList());
        try (JarFile sources = new JarFile(tempDir.resolve("target/demo-0.1.0-sources.jar").toFile())) {
            assertNotNull(sources.getEntry("com/example/App.java"));
            assertNotNull(sources.getEntry("com/example/GroovyApi.groovy"));
        }
        try (JarFile javadoc = new JarFile(tempDir.resolve("target/demo-0.1.0-javadoc.jar").toFile())) {
            assertTrue(javadoc.stream().anyMatch(entry -> entry.getName().endsWith("App.html")));
            assertFalse(javadoc.stream().anyMatch(entry -> entry.getName().contains("GroovyApi")));
        }
    }

    @Test
    void compiledFilesExcludeLocalBuildMetadata() throws IOException {
        Path output = tempDir.resolve("target/test-classes");
        write(output.resolve("com/example/AppTest.class"));
        write(output.resolve(".zolt-build-test.fingerprint"));
        write(output.resolve(".zolt-build-test.fingerprint.state"));
        write(output.resolve(".zolt-incremental-test.state"));

        assertEquals(
                List.of(output.resolve("com/example/AppTest.class")),
                PackageSupplementalArtifactFiles.compiledFiles(output));
    }

    @Test
    void deleteDirectoryRemovesNestedDirectory() throws IOException {
        Path directory = tempDir.resolve("target/javadoc");
        write(directory.resolve("com/example/App.html"));

        PackageSupplementalArtifactFiles.deleteDirectory(directory);

        assertFalse(Files.exists(directory));
    }

    private static void write(Path path) throws IOException {
        write(path, "content");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
