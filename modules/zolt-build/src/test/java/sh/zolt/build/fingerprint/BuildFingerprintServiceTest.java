package sh.zolt.build.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildFingerprintServiceTest {
    private final BuildFingerprintService service = new BuildFingerprintService();

    @TempDir
    private Path projectDir;

    @Test
    void rejectsUnsafeMainResourceRootBeforeFingerprinting() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        ProjectConfig config = config().withBuildSettings(new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                List.of(),
                List.of("../outside-resources"),
                List.of("src/test/resources"),
                null));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> service.writeMainCompileFingerprint(
                        projectDir,
                        config,
                        projectDir.resolve("zolt.lock"),
                        new SourceDiscoveryResult(List.of(), List.of()),
                        emptyClasspaths(),
                        projectDir.resolve("target/classes"),
                        projectDir.resolve("target/generated/sources/annotations")));

        assertTrue(exception.getMessage().contains("[resources].main"));
        assertTrue(exception.getMessage().contains("../outside-resources"));
    }

    @Test
    void rejectsUnsafeGeneratedSourceInputBeforeFingerprinting() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        ProjectConfig config = config().withBuildSettings(config().build().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "openapi",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/sources/openapi",
                        List.of("../api.yaml"),
                        true,
                        false)),
                List.of()));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> service.writeMainCompileFingerprint(
                        projectDir,
                        config,
                        projectDir.resolve("zolt.lock"),
                        new SourceDiscoveryResult(List.of(), List.of()),
                        emptyClasspaths(),
                        projectDir.resolve("target/classes"),
                        projectDir.resolve("target/generated/sources/annotations")));

        assertTrue(exception.getMessage().contains("[generated.openapi].inputs"));
        assertTrue(exception.getMessage().contains("../api.yaml"));
    }

    @Test
    void testCompileFingerprintTracksJavaGroovyAndGeneratedTestSources() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Path javaTest = write("src/test/java/com/example/AppTest.java", "final class AppTest {}\n");
        Path groovyTest = write("src/test/groovy/com/example/AppSpec.groovy", "class AppSpec {}\n");
        Path generatedTest = write(
                "target/generated/test-sources/fixtures/com/example/GeneratedTest.java",
                "final class GeneratedTest {}\n");
        write("src/test/fixtures/schema.json", "{}\n");
        write("target/test-classes/com/example/AppTest.class", "class");
        write("target/test-classes/com/example/AppSpec.class", "class");
        write("target/test-classes/com/example/GeneratedTest.class", "class");
        ProjectConfig config = config().withBuildSettings(config().build().withGeneratedSources(
                List.of(),
                List.of(new GeneratedSourceStep(
                        "fixtures",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/test-sources/fixtures",
                        List.of("src/test/fixtures/schema.json"),
                        true,
                        true))));
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(),
                List.of(javaTest, generatedTest),
                List.of(groovyTest));
        Path output = projectDir.resolve("target/test-classes");

        service.writeTestCompileFingerprint(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                List.of(),
                new Classpath(List.of()),
                new Classpath(List.of()),
                output,
                projectDir.resolve("target/generated/test-sources/annotations"));

        assertTrue(
                service.storedTestInputsFingerprintSha256(output)
                        .startsWith("sha256:"));
        assertTrue(service.isTestCompileCurrent(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                List.of(),
                new Classpath(List.of()),
                new Classpath(List.of()),
                output,
                projectDir.resolve("target/generated/test-sources/annotations")));

        Files.writeString(projectDir.resolve("src/test/fixtures/schema.json"), "{\"changed\":true}\n");

        assertFalse(service.isTestCompileCurrent(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                List.of(),
                new Classpath(List.of()),
                new Classpath(List.of()),
                output,
                projectDir.resolve("target/generated/test-sources/annotations")));
    }

    @Test
    void reportsTheFingerprintComponentThatChanged() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Path source = write("src/main/java/com/example/Main.java", "package com.example; final class Main {}\n");
        write("target/classes/com/example/Main.class", "class");
        SourceDiscoveryResult sources = new SourceDiscoveryResult(List.of(source), List.of());
        ProjectConfig config = config();
        Path output = projectDir.resolve("target/classes");

        service.writeMainCompileFingerprint(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                emptyClasspaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations"));
        Files.writeString(source, "package com.example; final class Main { int changed; }\n");

        BuildFingerprintCheck check = service.checkMainCompileCurrent(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                emptyClasspaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations"));

        assertFalse(check.current());
        assertEquals("fingerprint-mismatch:sources", check.reason());
    }

    @Test
    void refreshesChangedFilesWithoutDroppingCurrentCachedHashes() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        Path source = write("src/main/java/com/example/Main.java", "package com.example; final class Main {}\n");
        Path dependency = write("cache/example.jar", "dependency");
        write("target/classes/com/example/Main.class", "class");
        SourceDiscoveryResult sources = new SourceDiscoveryResult(List.of(source), List.of());
        ProjectConfig config = config();
        Path output = projectDir.resolve("target/classes");
        Classpath compile = new Classpath(List.of(dependency));
        Classpath empty = new Classpath(List.of());
        ClasspathSet classpaths = new ClasspathSet(compile, compile, compile, empty, empty, empty);
        Path fingerprint = output.resolve(".zolt-build-main.fingerprint");

        service.writeMainCompileFingerprint(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                classpaths,
                output,
                projectDir.resolve("target/generated/sources/annotations"));
        Files.writeString(source, "package com.example; final class Main { int changed; }\n");
        assertFalse(service.isMainCompileCurrent(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                classpaths,
                output,
                projectDir.resolve("target/generated/sources/annotations")));

        service.writeMainCompileFingerprint(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                classpaths,
                output,
                projectDir.resolve("target/generated/sources/annotations"));

        BuildFingerprintState refreshed = new BuildFingerprintStateStore()
                .readState(fingerprint)
                .orElseThrow();
        assertEquals(
                new BuildFingerprintFileHasher().fileHash(dependency, null, null),
                refreshed.hashIfCurrent(dependency).orElseThrow());
        assertTrue(service.isMainCompileCurrent(
                projectDir,
                config,
                projectDir.resolve("zolt.lock"),
                sources,
                classpaths,
                output,
                projectDir.resolve("target/generated/sources/annotations")));
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static ClasspathSet emptyClasspaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty);
    }

    private Path write(String relativePath, String content) throws IOException {
        Path path = projectDir.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
