package sh.zolt.build.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class BuildFingerprintKotlinSourceTest {
    private static final String COMPILER_IDENTITY = "test-compiler";
    private final BuildFingerprintService service = new BuildFingerprintService();

    @TempDir
    private Path projectDir;

    @Test
    void tracksKotlinSourcesAndActualCompilerOutputs() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Path source = write(
                "src/main/java/com/example/Main.kt",
                "package com.example\nclass Main\n");
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(),
                List.of(),
                List.of(source),
                List.of(),
                List.of(),
                List.of());
        Path output = projectDir.resolve("target/classes");
        write("target/classes/com/example/Main.class", "compiled");
        write("target/classes/com/example/Main$Companion.class", "compiled");
        write("target/classes/META-INF/demo.kotlin_module", "module");
        write("target/classes/META-INF/nested/ignored.kotlin_module", "not direct metadata");
        write("target/classes/application.properties", "not a compiler output");

        service.writeMainCompileFingerprint(
                projectDir,
                config(),
                COMPILER_IDENTITY,
                projectDir.resolve("zolt.lock"),
                sources,
                emptyClasspaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations"));
        String before = service.mainInputsFingerprintSha256(
                projectDir,
                config(),
                COMPILER_IDENTITY,
                projectDir.resolve("zolt.lock"),
                sources,
                emptyClasspaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations"));
        String fingerprint = Files.readString(output.resolve(".zolt-build-main.fingerprint"));

        Files.writeString(source, "package com.example\nclass Main(val changed: Int)\n");

        assertFalse(service.isMainCompileCurrent(
                projectDir,
                config(),
                COMPILER_IDENTITY,
                projectDir.resolve("zolt.lock"),
                sources,
                emptyClasspaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations")));
        assertNotEquals(before, service.mainInputsFingerprintSha256(
                projectDir,
                config(),
                COMPILER_IDENTITY,
                projectDir.resolve("zolt.lock"),
                sources,
                emptyClasspaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations")));
        assertTrue(fingerprint.contains("src/main/java/com/example/Main.kt|"));
        assertTrue(fingerprint.contains("[expectedClasses]\n"
                + "target/classes/META-INF/demo.kotlin_module\n"
                + "target/classes/com/example/Main$Companion.class\n"
                + "target/classes/com/example/Main.class\n"));
        assertFalse(fingerprint.contains("ignored.kotlin_module"));
        assertFalse(fingerprint.contains("application.properties"));
    }

    @Test
    void actualOutputInventoryIsOutputOnlyAndDetectsDeletedKotlinOutputs() throws IOException {
        Files.writeString(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n");
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        Path source = write("src/main/java/com/example/Main.kt", "package com.example\nclass Main\n");
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(), List.of(), List.of(source), List.of(), List.of(), List.of());
        Path output = projectDir.resolve("target/classes");
        Path mainClass = write("target/classes/com/example/Main.class", "compiled");
        Path module = write("target/classes/META-INF/demo.kotlin_module", "module");

        service.writeMainCompileFingerprint(
                projectDir,
                config(),
                COMPILER_IDENTITY,
                projectDir.resolve("zolt.lock"),
                sources,
                emptyClasspaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations"));
        String inputsBefore = service.mainInputsFingerprintSha256(
                projectDir,
                config(),
                COMPILER_IDENTITY,
                projectDir.resolve("zolt.lock"),
                sources,
                emptyClasspaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations"));
        write("target/classes/com/example/Additional.class", "compiled");
        assertEquals(inputsBefore, service.mainInputsFingerprintSha256(
                projectDir,
                config(),
                COMPILER_IDENTITY,
                projectDir.resolve("zolt.lock"),
                sources,
                emptyClasspaths(),
                output,
                projectDir.resolve("target/generated/sources/annotations")));

        BuildFingerprintOutputValidator validator = new BuildFingerprintOutputValidator();
        assertTrue(validator.mainOutputsCurrent(projectDir, output));
        Files.delete(module);
        assertFalse(validator.mainOutputsCurrent(projectDir, output));
        assertEquals(
                "missing-expected-compiler-output:target/classes/META-INF/demo.kotlin_module",
                service.checkMainCompileCurrent(
                                projectDir,
                                config(),
                                COMPILER_IDENTITY,
                                projectDir.resolve("zolt.lock"),
                                sources,
                                emptyClasspaths(),
                                output,
                                projectDir.resolve("target/generated/sources/annotations"))
                        .reason());

        Files.writeString(module, "module");
        Files.delete(mainClass);
        assertFalse(validator.mainOutputsCurrent(projectDir, output));
        assertEquals(
                "missing-expected-class:target/classes/com/example/Main.class",
                service.checkMainCompileCurrent(
                                projectDir,
                                config(),
                                COMPILER_IDENTITY,
                                projectDir.resolve("zolt.lock"),
                                sources,
                                emptyClasspaths(),
                                output,
                                projectDir.resolve("target/generated/sources/annotations"))
                        .reason());
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
