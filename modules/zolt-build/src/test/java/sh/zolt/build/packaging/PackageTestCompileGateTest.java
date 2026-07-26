package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprintService;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageTestCompileGateTest {
    @TempDir
    private Path tempDir;

    @Test
    void acceptsCurrentCanonicalTestCompileFingerprint()
            throws IOException {
        Fixture fixture = fixture("current");

        assertDoesNotThrow(() -> fixture.requireCurrent(
                fixture.config()));
    }

    @Test
    void rejectsTestSourceChangedAfterCompile() throws IOException {
        Fixture fixture = fixture("source");
        Files.writeString(
                fixture.project().resolve(
                        "src/test/java/com/example/DemoTest.java"),
                "package com.example; final class DemoTest { int changed; }\n");

        assertStale(fixture, fixture.config());
    }

    @Test
    void rejectsTestResourceChangedAfterCompile() throws IOException {
        Fixture fixture = fixture("resource");
        Files.writeString(
                fixture.project().resolve(
                        "src/test/resources/fixture.txt"),
                "changed\n");

        assertStale(fixture, fixture.config());
    }

    @Test
    void rejectsGeneratedTestInputChangedAfterCompile()
            throws IOException {
        Fixture fixture = fixture("generated-input");
        Files.writeString(
                fixture.project().resolve("fixtures.sql"),
                "changed\n");

        assertStale(fixture, fixture.config());
    }

    @Test
    void rejectsTestClasspathChangedAfterCompile()
            throws IOException {
        Fixture fixture = fixture("classpath");
        Files.writeString(
                fixture.dependencyJar(),
                "changed dependency bytes\n");

        assertStale(fixture, fixture.config());
    }

    @Test
    void rejectsTestCompilerOptionChangedAfterCompile()
            throws IOException {
        Fixture fixture = fixture("compiler");
        writeToml(fixture.project(), "-parameters");

        assertStale(
                fixture,
                new ZoltTomlParser().parse(
                        fixture.project().resolve("zolt.toml")));
    }

    @Test
    void rejectsMissingFingerprintWithNonemptyTestOutput()
            throws IOException {
        Fixture fixture = fixture("missing-fingerprint");
        Files.delete(fixture.testOutput().resolve(
                ".zolt-build-test.fingerprint"));

        PackageException exception = assertThrows(
                PackageException.class,
                () -> fixture.requireCurrent(fixture.config()));

        assertTrue(exception.getMessage().contains(
                "missing-fingerprint"));
        assertTrue(exception.getMessage().contains(
                "Run `zolt test`"));
    }

    private Fixture fixture(String name) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        writeToml(project, "-g");
        Files.writeString(
                project.resolve("zolt.lock"),
                "version = 1\n");
        write(
                project,
                "src/main/java/com/example/Main.java",
                "package com.example; public final class Main {}\n");
        write(
                project,
                "src/test/java/com/example/DemoTest.java",
                "package com.example; final class DemoTest {}\n");
        write(
                project,
                "src/test/resources/fixture.txt",
                "fixture\n");
        write(project, "fixtures.sql", "seed\n");
        Path mainClass = project.resolve(
                "target/classes/com/example/Main.class");
        Files.createDirectories(mainClass.getParent());
        Files.writeString(mainClass, "main bytecode");
        Path testClass = project.resolve(
                "target/test-classes/com/example/DemoTest.class");
        Files.createDirectories(testClass.getParent());
        Files.writeString(testClass, "test bytecode");
        Path dependencyJar = project.resolve("deps/test-api.jar");
        Files.createDirectories(dependencyJar.getParent());
        Files.writeString(dependencyJar, "dependency bytes");
        ProjectConfig config = new ZoltTomlParser().parse(
                project.resolve("zolt.toml"));
        ClasspathSet classpaths = classpaths(dependencyJar);
        SourceDiscoveryResult sources = new SourceDiscoverer()
                .discover(project, config.build());
        List<Path> testCompileEntries = new ArrayList<>();
        testCompileEntries.add(project.resolve("target/classes"));
        testCompileEntries.addAll(
                classpaths.testCompile().entries());
        new BuildFingerprintService().writeTestCompileFingerprint(
                project,
                config,
                project.resolve("zolt.lock"),
                sources,
                new GeneratedSourceProducerFingerprintService()
                        .fingerprintsTest(
                                project,
                                config,
                                List.of()),
                new Classpath(testCompileEntries),
                classpaths.testProcessor(),
                project.resolve("target/test-classes"),
                project.resolve(
                        "target/generated/test-sources/annotations"));
        return new Fixture(
                project,
                config,
                dependencyJar,
                project.resolve("target/test-classes"),
                classpaths,
                new PackageTestCompileGate(
                        new ZoltLockfileReader(),
                        new ClasspathBuilder()));
    }

    private static void assertStale(
            Fixture fixture,
            ProjectConfig config) {
        PackageException exception = assertThrows(
                PackageException.class,
                () -> fixture.requireCurrent(config));

        assertTrue(exception.getMessage().contains(
                "compiled test output is not current"));
        assertTrue(exception.getMessage().contains(
                "fingerprint-mismatch"));
        assertTrue(exception.getMessage().contains(
                "Run `zolt test`"));
    }

    private static ClasspathSet classpaths(Path dependencyJar) {
        Classpath empty = new Classpath(List.of());
        Classpath testCompile = new Classpath(
                List.of(dependencyJar));
        return new ClasspathSet(
                empty,
                empty,
                empty,
                testCompile,
                empty,
                empty,
                empty);
    }

    private static void writeToml(Path project, String testArg)
            throws IOException {
        Files.writeString(
                project.resolve("zolt.toml"),
                """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"

                [compiler]
                testArgs = ["%s"]

                [generated.test.fixtures]
                kind = "declared-root"
                language = "java"
                inputs = ["fixtures.sql"]
                output = "target/generated/test-fixtures"
                required = false

                [package]
                tests = true
                """.formatted(
                        currentJavaMajorVersion(),
                        testArg));
    }

    private static void write(
            Path project,
            String relative,
            String content) throws IOException {
        Path path = project.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0])
                ? parts[1]
                : parts[0];
    }

    private record Fixture(
            Path project,
            ProjectConfig config,
            Path dependencyJar,
            Path testOutput,
            ClasspathSet classpaths,
            PackageTestCompileGate gate) {
        void requireCurrent(ProjectConfig currentConfig) {
            gate.requireCurrent(
                    project,
                    currentConfig,
                    new BuildResult(
                            Optional.empty(),
                            1,
                            0,
                            project.resolve("target/classes"),
                            ""),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(classpaths));
        }
    }
}
