package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprintServiceTestSupport.service;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.build.classpath.ClasspathBuilder;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprintService;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageTestGeneratedProducerFreshnessTest {
    @TempDir
    private Path tempDir;

    @Test
    void rejectsChangedExecGlobMatch() throws IOException {
        Path project = project("glob");
        Path toolJar = write(
                project,
                "tools/gen.jar",
                "tool-v1");
        Path schema = write(
                project,
                "schemas/api/model.json",
                "{\"v\":1}\n");
        List<ResolvedClasspathPackage> packages =
                jvmPackages(toolJar);
        Fixture fixture = fixture(
                project,
                jvmStep("inputs = [\"schemas/**/*.json\"]"),
                packages,
                service(Map.of(), () -> ""));

        Files.writeString(schema, "{\"v\":2}\n");

        assertStale(fixture);
    }

    @Test
    void rejectsChangedInheritedEnvironmentValue()
            throws IOException {
        Path project = project("env");
        Path toolJar = write(
                project,
                "tools/gen.jar",
                "tool-v1");
        write(project, "schemas/api/model.json", "{}\n");
        Map<String, String> environment = new HashMap<>();
        environment.put("SCHEMA_VERSION", "v1");
        Fixture fixture = fixture(
                project,
                jvmStep("""
                        inputs = ["schemas/**/*.json"]
                        inheritEnv = ["SCHEMA_VERSION"]
                        """),
                jvmPackages(toolJar),
                service(environment, () -> ""));

        environment.put("SCHEMA_VERSION", "v2");

        assertStale(fixture);
    }

    @Test
    void rejectsChangedProcessToolReportedVersion()
            throws IOException {
        Path project = project("process");
        Path bin = project.resolve("bin");
        Files.createDirectories(bin);
        executable(bin.resolve("zoltgen"));
        executable(bin.resolve("zoltprobe"));
        AtomicReference<String> version =
                new AtomicReference<>("1.0.0");
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", bin.toString());
        Fixture fixture = fixture(
                project,
                processStep(),
                List.of(),
                service(environment, version::get));

        version.set("2.0.0");

        assertStale(fixture);
    }

    @Test
    void rejectsChangedResolvedJvmGeneratorJar()
            throws IOException {
        Path project = project("jvm-tool");
        Path toolJar = write(
                project,
                "tools/gen.jar",
                "tool-v1");
        write(project, "schemas/api/model.json", "{}\n");
        Fixture fixture = fixture(
                project,
                jvmStep("inputs = [\"schemas/**/*.json\"]"),
                jvmPackages(toolJar),
                service(Map.of(), () -> ""));

        Files.writeString(toolJar, "tool-v2");

        assertStale(fixture);
    }

    @Test
    void rejectsChangedPostCompileTestResourceProducer()
            throws IOException {
        Path project = project("post-compile");
        Map<String, String> environment = new HashMap<>();
        environment.put("POST_VERSION", "v1");
        Fixture fixture = fixture(
                project,
                """
                [generated.test.fixtures]
                kind = "exec"
                tool = "project"
                mainClass = "com.example.Main"
                inputs = ["seed.txt"]
                inheritEnv = ["POST_VERSION"]
                output = "target/generated/test-resources"
                produces = "test-resources"
                required = false
                """,
                List.of(),
                service(environment, () -> ""));

        fixture.requireCurrent();
        environment.put("POST_VERSION", "v2");

        assertStale(fixture);
    }

    private Fixture fixture(
            Path project,
            String generatedConfig,
            List<ResolvedClasspathPackage> packages,
            GeneratedSourceProducerFingerprintService
                    producerService) throws IOException {
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "%s"

                %s
                [package]
                tests = true
                """.formatted(
                System.getProperty(
                        "java.specification.version"),
                generatedConfig));
        Files.writeString(
                project.resolve("zolt.lock"),
                "version = 1\n");
        write(
                project,
                "src/test/java/com/example/DemoTest.java",
                "package com.example; final class DemoTest {}\n");
        write(
                project,
                "target/classes/com/example/Main.class",
                "main-bytecode");
        write(
                project,
                "target/test-classes/com/example/DemoTest.class",
                "test-bytecode");
        write(project, "seed.txt", "seed\n");
        ProjectConfig config = new ZoltTomlParser().parse(
                project.resolve("zolt.toml"));
        ClasspathSet classpaths =
                new ClasspathBuilder().build(packages);
        SourceDiscoveryResult sources =
                new SourceDiscoverer().discover(
                        project,
                        config.build());
        List<Path> testCompileEntries = new ArrayList<>();
        testCompileEntries.add(
                project.resolve("target/classes"));
        testCompileEntries.addAll(
                classpaths.testCompile().entries());
        new BuildFingerprintService()
                .writeTestCompileFingerprint(
                        project,
                        config,
                        project.resolve("zolt.lock"),
                        sources,
                        producerService.fingerprintsTest(
                                project,
                                config,
                                packages),
                        new Classpath(testCompileEntries),
                        classpaths.testProcessor(),
                        project.resolve(
                                "target/test-classes"),
                        project.resolve(
                                "target/generated/test-sources/annotations"));
        return new Fixture(
                project,
                config,
                packages,
                classpaths,
                new PackageTestCompileGate(
                        new SourceDiscoverer(),
                        new BuildFingerprintService(),
                        new ZoltLockfileReader(),
                        new ClasspathBuilder(),
                        new PackageRuntimeJarSelector(),
                        producerService));
    }

    private static void assertStale(Fixture fixture) {
        PackageException exception = assertThrows(
                PackageException.class,
                fixture::requireCurrent);

        assertTrue(exception.getMessage().contains(
                "fingerprint-mismatch:generatedProducerFingerprints"));
        assertTrue(exception.getMessage().contains(
                "Run `zolt test`"));
    }

    private Path project(String name) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        return project;
    }

    private static String jvmStep(String fields) {
        return """
                [generated.execTools.gen]
                runner = "jvm"
                coordinates = [{ coordinate = "com.example:gen", version = "1.0.0" }]
                mainClass = "com.example.Gen"

                [generated.test.fixtures]
                kind = "exec"
                tool = "gen"
                output = "target/generated/test-fixtures"
                produces = "test-sources"
                required = false
                %s
                """.formatted(fields);
    }

    private static String processStep() {
        return """
                [generated.execTools.gen]
                runner = "process"
                binary = "zoltgen"
                versionCommand = ["zoltprobe"]
                allowUnpinnedTool = true

                [generated.test.fixtures]
                kind = "exec"
                tool = "gen"
                inputs = ["seed.txt"]
                output = "target/generated/test-resources"
                produces = "test-resources"
                required = false
                """;
    }

    private static List<ResolvedClasspathPackage> jvmPackages(
            Path toolJar) {
        return List.of(new ResolvedClasspathPackage(
                new ResolvedPackage(
                        new PackageId("com.example", "gen"),
                        "1.0.0",
                        true,
                        Path.of("gen.pom"),
                        toolJar),
                DependencyScope.TOOL_EXEC,
                List.of("gen")));
    }

    private static Path write(
            Path project,
            String relative,
            String content) throws IOException {
        Path path = project.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private static void executable(Path path)
            throws IOException {
        Files.writeString(path, "placeholder\n");
        path.toFile().setExecutable(true);
    }

    private record Fixture(
            Path project,
            ProjectConfig config,
            List<ResolvedClasspathPackage> packages,
            ClasspathSet classpaths,
            PackageTestCompileGate gate) {
        void requireCurrent() {
            gate.requireCurrent(
                    project,
                    config,
                    new BuildResult(
                            Optional.empty(),
                            1,
                            0,
                            project.resolve(
                                    "target/classes"),
                            ""),
                    Optional.empty(),
                    Optional.of(packages),
                    Optional.of(classpaths));
        }
    }
}
