package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.ide.IdeContentAddressedLockTestSupport.cachedJar;
import static sh.zolt.ide.IdeContentAddressedLockTestSupport.write;

import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdeClasspathModelBuilderTest {
    private final IdeClasspathModelBuilder builder = new IdeClasspathModelBuilder();

    @TempDir
    private Path tempDir;

    @Test
    void exportsLockfileClasspathsWithProjectOutputs() throws IOException {
        Path projectDir = tempDir.resolve("spring-boot-webmvc");
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(projectDir);
        Files.copy(exampleProjectConfig(), projectDir.resolve("zolt.toml"));
        write(projectDir.resolve("zolt.lock"), cacheRoot, """
                version = 1

                [[package]]
                id = "org.springframework.boot:spring-boot-starter-webmvc"
                version = "4.0.6"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "org/springframework/boot/spring-boot-starter-webmvc/4.0.6/spring-boot-starter-webmvc-4.0.6.jar"
                dependencies = ["org.springframework:spring-webmvc"]

                [[package]]
                id = "org.springframework:spring-webmvc"
                version = "7.0.5"
                source = "maven-central"
                scope = "compile"
                direct = false
                jar = "org/springframework/spring-webmvc/7.0.5/spring-webmvc-7.0.5.jar"
                dependencies = []

                [[package]]
                id = "org.projectlombok:lombok"
                version = "1.18.42"
                source = "maven-central"
                scope = "processor"
                direct = true
                jar = "org/projectlombok/lombok/1.18.42/lombok-1.18.42.jar"
                dependencies = []

                [[package]]
                id = "com.example:test-processor"
                version = "1.0.0"
                source = "maven-central"
                scope = "test-processor"
                direct = true
                jar = "com/example/test-processor/1.0.0/test-processor-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "io.quarkus:quarkus-rest-deployment"
                version = "3.33.2"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                jar = "io/quarkus/quarkus-rest-deployment/3.33.2/quarkus-rest-deployment-3.33.2.jar"
                dependencies = []
                """);
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.ClasspathInfo classpaths = builder.build(
                projectDir.resolve("zolt.lock").toAbsolutePath().normalize(),
                cacheRoot.toAbsolutePath().normalize(),
                projectDir.toAbsolutePath().normalize(),
                parse(projectDir),
                diagnostics);

        Path root = projectDir.toAbsolutePath().normalize();
        Path absoluteCache = cacheRoot.toAbsolutePath().normalize();
        Path lockfile = projectDir.resolve("zolt.lock");
        Path springBoot = cachedJar(
                lockfile, absoluteCache, "org.springframework.boot:spring-boot-starter-webmvc");
        Path springWebMvc = cachedJar(lockfile, absoluteCache, "org.springframework:spring-webmvc");
        Path lombok = cachedJar(lockfile, absoluteCache, "org.projectlombok:lombok");
        Path testProcessor = cachedJar(lockfile, absoluteCache, "com.example:test-processor");
        Path quarkusDeployment = cachedJar(
                lockfile, absoluteCache, "io.quarkus:quarkus-rest-deployment");
        assertEquals(List.of(
                springBoot,
                springWebMvc),
                classpaths.compile());
        assertEquals(List.of(
                root.resolve("target/classes"),
                springBoot,
                springWebMvc),
                classpaths.runtime());
        assertEquals(List.of(lombok), classpaths.processor());
        assertEquals(List.of(testProcessor), classpaths.testProcessor());
        assertEquals(List.of(quarkusDeployment), classpaths.quarkusDeployment());
        assertEquals(List.of(), diagnostics);

        String json = new IdeModelJsonWriter().write(modelWith(classpaths, diagnostics));
        assertTrue(json.contains("\"processor\": ["));
        assertTrue(json.contains("\"testProcessor\": ["));
        assertTrue(json.contains("\"quarkusDeployment\": ["));
    }

    @Test
    void reportsCacheIntegrityFailureInsteadOfExportingPoisonedClasspaths() throws IOException {
        Path projectDir = tempDir.resolve("corrupted-cache-model");
        Path cacheRoot = tempDir.resolve("cache-corrupted");
        Files.createDirectories(projectDir);
        String checksum = "0".repeat(64);
        Path jar = cacheRoot.resolve("blobs/v2/sha256/" + checksum + "/runtime-lib-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "corrupted runtime jar bytes");
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "corrupted-cache-model"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 6

                [[package]]
                id = "com.example:runtime-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "blobs/v2/sha256/%s/runtime-lib-1.0.0.jar"
                jarSha256 = "%s"
                dependencies = []
                """.formatted(checksum, checksum));
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.ClasspathInfo classpaths = builder.build(
                projectDir.resolve("zolt.lock").toAbsolutePath().normalize(),
                cacheRoot.toAbsolutePath().normalize(),
                projectDir.toAbsolutePath().normalize(),
                parse(projectDir),
                diagnostics);

        assertEquals(List.of(), classpaths.runtime());
        IdeModel.Diagnostic diagnostic = diagnostics.getFirst();
        assertEquals("LOCKFILE_INTEGRITY_FAILED", diagnostic.code());
        assertTrue(diagnostic.message().contains(
                "Cached jar integrity check failed for com.example:runtime-lib:1.0.0"));
    }

    @Test
    void reportsUnreadableLockfileWithoutExportingClasspaths() throws IOException {
        Path projectDir = tempDir.resolve("unreadable-lock-model");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "unreadable-lock-model"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = \"one\"\n");
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.ClasspathInfo classpaths = builder.build(
                projectDir.resolve("zolt.lock").toAbsolutePath().normalize(),
                tempDir.resolve("cache").toAbsolutePath().normalize(),
                projectDir.toAbsolutePath().normalize(),
                parse(projectDir),
                diagnostics);

        assertEquals(List.of(), classpaths.compile());
        assertEquals(List.of(), classpaths.runtime());
        assertEquals(List.of(), classpaths.test());
        assertEquals(List.of(), classpaths.processor());
        assertEquals(List.of(), classpaths.testProcessor());
        assertEquals(List.of(), classpaths.quarkusDeployment());
        IdeModel.Diagnostic diagnostic = diagnostics.getFirst();
        assertEquals("LOCKFILE_UNREADABLE", diagnostic.code());
        assertTrue(diagnostic.message().contains("zolt.lock"));
        assertEquals("Run zolt resolve.", diagnostic.nextStep());
    }

    @Test
    void returnsEmptyClasspathsWithoutLockfileDiagnosticWhenProjectConfigIsMissing() {
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.ClasspathInfo classpaths = builder.build(
                tempDir.resolve("missing/zolt.lock"),
                tempDir.resolve("cache"),
                tempDir.resolve("missing"),
                null,
                diagnostics);

        assertEquals(List.of(), classpaths.compile());
        assertEquals(List.of(), classpaths.runtime());
        assertEquals(List.of(), classpaths.test());
        assertEquals(List.of(), classpaths.processor());
        assertEquals(List.of(), classpaths.testProcessor());
        assertEquals(List.of(), classpaths.quarkusDeployment());
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void suppressesDuplicateUnsafeOutputDiagnosticsAcrossRepeatedClasspathBuilds() throws IOException {
        Path projectDir = tempDir.resolve("duplicate-output-diagnostic");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "duplicate-output-diagnostic"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [build]
                output = "../outside-classes"
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 1\n");
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        builder.build(
                projectDir.resolve("zolt.lock").toAbsolutePath().normalize(),
                tempDir.resolve("cache").toAbsolutePath().normalize(),
                projectDir.toAbsolutePath().normalize(),
                parse(projectDir),
                diagnostics);
        builder.build(
                projectDir.resolve("zolt.lock").toAbsolutePath().normalize(),
                tempDir.resolve("cache").toAbsolutePath().normalize(),
                projectDir.toAbsolutePath().normalize(),
                parse(projectDir),
                diagnostics);

        assertEquals(1, diagnostics.size());
        IdeModel.Diagnostic diagnostic = diagnostics.getFirst();
        assertEquals("PROJECT_PATH_INVALID", diagnostic.code());
        assertTrue(diagnostic.message().contains("[build].output"));
        assertEquals(
                "Fix the unsafe path in zolt.toml and run zolt ide model --format json again.",
                diagnostic.nextStep());
    }

    private ProjectConfig parse(Path projectDir) {
        return new ZoltTomlParser().parse(projectDir.resolve("zolt.toml"));
    }

    private static Path exampleProjectConfig() {
        Path fromRoot = Path.of("examples/spring-boot-webmvc/zolt.toml");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        return Path.of("../..").resolve(fromRoot).normalize();
    }

    private IdeModel modelWith(IdeModel.ClasspathInfo classpaths, List<IdeModel.Diagnostic> diagnostics) {
        return new IdeModel(
                1,
                new IdeModel.ProjectInfo("classpath", "com.example", "0.1.0", null),
                new IdeModel.JavaInfo("21", null, null),
                new IdeModel.CompilerInfo(null, null, null, List.of(), List.of(), null, null),
                new IdeModel.TestRuntimeInfo(List.of(), Map.of(), Map.of(), List.of()),
                new IdeModel.PackageInfo(
                        null,
                        false,
                        false,
                        false,
                        null,
                        null,
                        null,
                        null,
                        new IdeModel.PublicationInfo(null, null, null, null, List.of(), null, null),
                        Map.of()),
                new IdeModel.PathInfo(tempDir, tempDir.resolve("zolt.toml"), tempDir.resolve("zolt.lock")),
                List.of(),
                List.of(),
                List.of(),
                new IdeModel.OutputInfo(null, null, null),
                new IdeModel.DependencyInfo(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                classpaths,
                new IdeModel.FrameworkInfo(new IdeModel.QuarkusInfo(
                        false,
                        null,
                        "disabled",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of())),
                diagnostics);
    }
}
