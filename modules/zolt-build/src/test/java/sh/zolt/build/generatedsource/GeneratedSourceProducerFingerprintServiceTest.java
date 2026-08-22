package sh.zolt.build.generatedsource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GeneratedSourceProducerFingerprintServiceTest {
    @TempDir
    private Path projectDir;

    @Test
    void execGlobFingerprintTracksAddedRemovedAndChangedMatches()
            throws IOException {
        seedJvmTool();
        Path schemas = projectDir.resolve("schemas/api");
        Files.createDirectories(schemas);
        Path first = schemas.resolve("first.json");
        Files.writeString(first, "{\"v\":1}\n");
        ProjectConfig config = jvmConfig(
                "inputs = [\"schemas/**/*.json\"]",
                "model");
        GeneratedSourceProducerFingerprintService service = service(Map.of());

        String initial = fingerprint(service, config, "model");
        Files.writeString(first, "{\"v\":2}\n");
        String changed = fingerprint(service, config, "model");
        Path second = schemas.resolve("second.json");
        Files.writeString(second, "{\"v\":3}\n");
        String added = fingerprint(service, config, "model");
        Files.delete(first);
        String removed = fingerprint(service, config, "model");

        assertNotEquals(initial, changed);
        assertNotEquals(changed, added);
        assertNotEquals(added, removed);
    }

    @Test
    void execFingerprintTracksInheritedEnvironmentValue() throws IOException {
        seedJvmTool();
        ProjectConfig config = jvmConfig(
                "inputs = [\"schemas/**/*.json\"]\ninheritEnv = [\"API_VERSION\"]",
                "model");
        Files.createDirectories(projectDir.resolve("schemas"));

        String first = fingerprint(service(Map.of("API_VERSION", "v1")), config, "model");
        String second = fingerprint(service(Map.of("API_VERSION", "v2")), config, "model");

        assertNotEquals(first, second);
    }

    @Test
    void execFingerprintTracksProcessToolProbeVersion() throws IOException {
        Path bin = projectDir.resolve("bin");
        Files.createDirectories(bin);
        executable(bin.resolve("zoltgen"));
        Files.writeString(projectDir.resolve("seed.txt"), "seed\n");
        AtomicReference<String> version = new AtomicReference<>("1.0.0");
        GeneratedSourceProducerFingerprintService service =
                new GeneratedSourceProducerFingerprintService(
                        ":",
                        (command, directory, environment, timeout) ->
                                new ExecGeneratedSourceService.ProcessResult(
                                        0,
                                        version.get(),
                                        false),
                        name -> "PATH".equals(name) ? bin.toString() : null);
        ProjectConfig config = processConfig("");

        String first = fingerprint(service, config, "assets", List.of());
        version.set("2.0.0");
        String second = fingerprint(service, config, "assets", List.of());

        assertNotEquals(first, second);
    }

    @Test
    void execFingerprintTracksResolvedJvmToolBytes() throws IOException {
        seedJvmTool();
        ProjectConfig config = jvmConfig(
                "inputs = [\"schemas/**/*.json\"]",
                "model");
        Files.createDirectories(projectDir.resolve("schemas"));
        GeneratedSourceProducerFingerprintService service = service(Map.of());

        String first = fingerprint(service, config, "model");
        Files.writeString(toolJar(), "changed tool bytes\n");
        String second = fingerprint(service, config, "model");

        assertNotEquals(first, second);
    }

    @Test
    void generatedStepDeclarationOrderDoesNotChangeFingerprints()
            throws IOException {
        seedJvmTool();
        Files.createDirectories(projectDir.resolve("schemas"));
        GeneratedSourceProducerFingerprintService service = service(Map.of());

        Map<String, String> first = fingerprintsByStep(
                service.fingerprintsMain(
                        projectDir,
                        twoStepConfig("alpha", "beta"),
                        packages()));
        Map<String, String> reversed = fingerprintsByStep(
                service.fingerprintsMain(
                        projectDir,
                        twoStepConfig("beta", "alpha"),
                        packages()));

        assertEquals(first, reversed);
        assertEquals(List.of("alpha", "beta"), first.keySet().stream().toList());
    }

    @Test
    void scopeSelectionDoesNotProbeAnUnselectedTestProducer()
            throws IOException {
        Files.writeString(projectDir.resolve("fixtures.sql"), "seed\n");
        GeneratedSourceProducerFingerprintService service = service(Map.of());
        ProjectConfig config = unavailableTestProcessConfig();

        assertEquals(
                List.of(),
                service.fingerprintsMain(
                        projectDir,
                        config,
                        List.of()));
        BuildException exception = assertThrows(
                BuildException.class,
                () -> service.fingerprintsTest(
                        projectDir,
                        config,
                        List.of()));

        assertTrue(exception.actionableError()
                .summary()
                .contains(
                        "[generated.test.fixtures] could not find process binary "
                                + "`zolt-missing-test-generator`"));
    }

    private GeneratedSourceProducerFingerprintService service(
            Map<String, String> environment) {
        return new GeneratedSourceProducerFingerprintService(
                ":",
                (command, directory, processEnvironment, timeout) ->
                        new ExecGeneratedSourceService.ProcessResult(
                                0,
                                "",
                                false),
                environment::get);
    }

    private String fingerprint(
            GeneratedSourceProducerFingerprintService service,
            ProjectConfig config,
            String stepId) {
        return fingerprint(service, config, stepId, packages());
    }

    private String fingerprint(
            GeneratedSourceProducerFingerprintService service,
            ProjectConfig config,
            String stepId,
            List<ResolvedClasspathPackage> packages) {
        return service.fingerprintsMain(projectDir, config, packages).stream()
                .filter(value -> value.stepId().equals(stepId))
                .findFirst()
                .orElseThrow()
                .fingerprint();
    }

    private void seedJvmTool() throws IOException {
        Files.createDirectories(toolJar().getParent());
        Files.writeString(toolJar(), "tool bytes\n");
    }

    private Path toolJar() {
        return projectDir.resolve(
                "cache/org/jooq/jooq-codegen/3.19.15/jooq-codegen-3.19.15.jar");
    }

    private List<ResolvedClasspathPackage> packages() {
        return ExecGeneratedSourceServiceTestSupport.packages(
                projectDir);
    }

    private static Map<String, String> fingerprintsByStep(
            List<GeneratedSourceProducerFingerprint> fingerprints) {
        Map<String, String> byStep = new LinkedHashMap<>();
        fingerprints.forEach(fingerprint -> byStep.put(
                fingerprint.stepId(),
                fingerprint.fingerprint()));
        return byStep;
    }

    private static ProjectConfig jvmConfig(String stepFields, String stepId) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.jooq]
                kind = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", version = "3.19.15" }]
                mainClass = "com.example.GenerationTool"

                [generated.main.%s]
                kind = "exec"
                tool = "jooq"
                output = "target/generated/sources/%s"
                produces = "java-sources"
                %s
                """.formatted(stepId, stepId, stepFields));
    }

    private static ProjectConfig twoStepConfig(String first, String second) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.jooq]
                kind = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", version = "3.19.15" }]
                mainClass = "com.example.GenerationTool"

                [generated.main.%s]
                kind = "exec"
                tool = "jooq"
                inputs = ["schemas/**/*.json"]
                output = "target/generated/sources/%s"
                produces = "java-sources"

                [generated.main.%s]
                kind = "exec"
                tool = "jooq"
                inputs = ["schemas/**/*.json"]
                output = "target/generated/sources/%s"
                produces = "java-sources"
                """.formatted(first, first, second, second));
    }

    private static ProjectConfig processConfig(String stepFields) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.gen]
                kind = "process"
                binary = "zoltgen"
                versionCommand = ["zoltgen", "--version"]
                allowUnpinnedTool = true

                [generated.main.assets]
                kind = "exec"
                tool = "gen"
                inputs = ["seed.txt"]
                output = "target/generated/assets"
                produces = "resources"
                %s
                """.formatted(stepFields));
    }

    private static ProjectConfig unavailableTestProcessConfig() {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.tools.test-generator]
                kind = "process"
                binary = "zolt-missing-test-generator"
                versionCommand = ["zolt-missing-test-generator", "--version"]
                allowUnpinnedTool = true

                [generated.test.fixtures]
                kind = "exec"
                tool = "test-generator"
                inputs = ["fixtures.sql"]
                output = "target/generated/test-fixtures"
                produces = "test-resources"
                """);
    }

    private static void executable(Path path) throws IOException {
        Files.writeString(path, "placeholder\n");
        path.toFile().setExecutable(true);
    }
}
