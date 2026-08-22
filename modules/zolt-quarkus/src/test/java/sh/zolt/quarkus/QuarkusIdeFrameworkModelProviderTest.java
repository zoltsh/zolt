package sh.zolt.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.ide.IdeModel;
import sh.zolt.ide.IdeModelJsonWriter;
import sh.zolt.project.ProjectConfig;
import sh.zolt.quarkus.production.QuarkusAugmentationMetadataWriter;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuarkusIdeFrameworkModelProviderTest {
    private final QuarkusIdeFrameworkModelProvider builder = new QuarkusIdeFrameworkModelProvider();

    @TempDir
    private Path tempDir;

    @Test
    void exportsDisabledQuarkusFrameworkStateWithoutDiagnostics() throws IOException {
        Path projectDir = tempDir.resolve("plain-java");
        ProjectConfig config = plainProject(projectDir);
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.FrameworkInfo frameworks = builder.build(
                projectDir.toAbsolutePath().normalize(),
                tempDir.resolve("cache"),
                config,
                diagnostics);

        IdeModel.QuarkusInfo quarkus = frameworks.quarkus();
        assertFalse(quarkus.enabled());
        assertEquals("disabled", quarkus.augmentationStatus());
        assertNull(quarkus.packageMode());
        assertEquals(List.of(), quarkus.generatedOutputs());
        assertEquals(List.of(), quarkus.deploymentClasspath());
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void exportsUnknownQuarkusFrameworkStateWhenCacheRootIsUnavailable() throws IOException {
        Path projectDir = tempDir.resolve("quarkus-no-cache");
        ProjectConfig config = quarkusProject(projectDir);
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.FrameworkInfo frameworks = builder.build(
                projectDir.toAbsolutePath().normalize(),
                null,
                config,
                diagnostics);

        Path root = projectDir.toAbsolutePath().normalize();
        IdeModel.QuarkusInfo quarkus = frameworks.quarkus();
        assertTrue(quarkus.enabled());
        assertEquals("fast-jar", quarkus.packageMode());
        assertEquals("unknown", quarkus.augmentationStatus());
        assertNull(quarkus.inputFingerprint());
        assertNull(quarkus.recordedInputFingerprint());
        assertEquals(root.resolve("target/quarkus/zolt-augmentation.properties"), quarkus.augmentationMetadata());
        assertEquals(root.resolve("target/quarkus-app/quarkus-run.jar"), quarkus.runnerJar());
        assertEquals(List.of(), quarkus.deploymentClasspath());
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void exportsQuarkusFrameworkStateWithMissingAugmentationDiagnostic() throws IOException {
        Path projectDir = tempDir.resolve("quarkus-missing");
        ProjectConfig config = quarkusProject(projectDir);
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.FrameworkInfo frameworks = builder.build(
                projectDir.toAbsolutePath().normalize(),
                tempDir.resolve("cache"),
                config,
                diagnostics);

        Path root = projectDir.toAbsolutePath().normalize();
        IdeModel.QuarkusInfo quarkus = frameworks.quarkus();
        assertTrue(quarkus.enabled());
        assertEquals("fast-jar", quarkus.packageMode());
        assertEquals("missing", quarkus.augmentationStatus());
        assertEquals(root.resolve("target/quarkus"), quarkus.augmentationDirectory());
        assertEquals(root.resolve("target/quarkus-app"), quarkus.packageDirectory());
        assertEquals(root.resolve("target/quarkus-app/quarkus-run.jar"), quarkus.runnerJar());
        assertEquals(root.resolve("target/quarkus-app/quarkus/generated-bytecode.jar"), quarkus.generatedBytecodeJar());
        assertEquals(root.resolve("target/quarkus-app/quarkus/transformed-bytecode.jar"), quarkus.transformedBytecodeJar());
        assertEquals(3, quarkus.generatedOutputs().size());
        assertGeneratedOutput(
                quarkus.generatedOutputs().get(0),
                "runner-jar",
                "runner-jar",
                root.resolve("target/quarkus-app/quarkus-run.jar"),
                false);
        assertGeneratedOutput(
                quarkus.generatedOutputs().get(1),
                "generated-bytecode",
                "generated-bytecode-jar",
                root.resolve("target/quarkus-app/quarkus/generated-bytecode.jar"),
                false);
        assertGeneratedOutput(
                quarkus.generatedOutputs().get(2),
                "transformed-bytecode",
                "transformed-bytecode-jar",
                root.resolve("target/quarkus-app/quarkus/transformed-bytecode.jar"),
                false);
        assertEquals(List.of(), quarkus.deploymentClasspath());
        assertEquals("QUARKUS_AUGMENTATION_MISSING", diagnostics.getFirst().code());

        String json = new IdeModelJsonWriter().write(modelWith(frameworks, diagnostics));
        assertTrue(json.contains("\"frameworks\": {"));
        assertTrue(json.contains("\"quarkus\": {"));
        assertTrue(json.contains("\"augmentationStatus\": \"missing\""));
        assertTrue(json.contains("\"generatedBytecodeJar\": \""));
        assertTrue(json.contains("\"generatedOutputs\": ["));
        assertTrue(json.contains("\"kind\": \"generated-bytecode-jar\""));
        assertTrue(json.contains("\"exists\": false"));
        assertTrue(json.contains("\"deploymentClasspath\": []"));
    }

    @Test
    void exportsCurrentQuarkusFrameworkStateWithoutAugmentationDiagnostic() throws IOException {
        Path projectDir = tempDir.resolve("quarkus-current");
        ProjectConfig config = quarkusProject(projectDir);
        Path root = projectDir.toAbsolutePath().normalize();
        List<IdeModel.Diagnostic> missingDiagnostics = new ArrayList<>();
        IdeModel.FrameworkInfo missingFrameworks = builder.build(root, tempDir.resolve("cache"), config, missingDiagnostics);
        new QuarkusAugmentationMetadataWriter().write(
                root,
                missingFrameworks.quarkus().inputFingerprint());
        writeGeneratedOutput(root.resolve("target/quarkus-app/quarkus-run.jar"));
        writeGeneratedOutput(root.resolve("target/quarkus-app/quarkus/generated-bytecode.jar"));
        writeGeneratedOutput(root.resolve("target/quarkus-app/quarkus/transformed-bytecode.jar"));
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.FrameworkInfo frameworks = builder.build(root, tempDir.resolve("cache"), config, diagnostics);

        assertEquals("current", frameworks.quarkus().augmentationStatus());
        assertEquals(
                frameworks.quarkus().inputFingerprint(),
                frameworks.quarkus().recordedInputFingerprint());
        assertTrue(frameworks.quarkus().generatedOutputs().stream().allMatch(IdeModel.QuarkusGeneratedOutput::exists));
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void reportsUnavailableQuarkusModelWhenLockfileCannotBeRead() throws IOException {
        Path projectDir = tempDir.resolve("quarkus-bad-lock");
        ProjectConfig config = quarkusProject(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), "version =\n");
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.FrameworkInfo frameworks = builder.build(
                projectDir.toAbsolutePath().normalize(),
                tempDir.resolve("cache"),
                config,
                diagnostics);

        IdeModel.QuarkusInfo quarkus = frameworks.quarkus();
        assertTrue(quarkus.enabled());
        assertEquals("unknown", quarkus.augmentationStatus());
        assertNull(quarkus.inputFingerprint());
        assertEquals(List.of(), quarkus.deploymentClasspath());
        assertEquals(1, diagnostics.size());
        assertEquals("warning", diagnostics.getFirst().severity());
        assertEquals("QUARKUS_MODEL_UNAVAILABLE", diagnostics.getFirst().code());
        assertTrue(diagnostics.getFirst().message().contains("zolt.lock"));
        assertEquals(projectDir.toAbsolutePath().normalize().resolve("zolt.lock"), diagnostics.getFirst().path());
        assertEquals("Run zolt resolve, then run zolt build.", diagnostics.getFirst().nextStep());
    }

    @Test
    void exportsQuarkusFrameworkStateFromConfiguredOutputRoot() throws IOException {
        Path projectDir = tempDir.resolve("quarkus-output-root");
        ProjectConfig config = quarkusProject(projectDir, ".zolt/build");
        List<IdeModel.Diagnostic> diagnostics = new ArrayList<>();

        IdeModel.FrameworkInfo frameworks = builder.build(
                projectDir.toAbsolutePath().normalize(),
                tempDir.resolve("cache"),
                config,
                diagnostics);

        Path root = projectDir.toAbsolutePath().normalize();
        IdeModel.QuarkusInfo quarkus = frameworks.quarkus();
        assertEquals(root.resolve(".zolt/build/quarkus/zolt-augmentation.properties"), quarkus.augmentationMetadata());
        assertEquals(root.resolve(".zolt/build/quarkus"), quarkus.augmentationDirectory());
        assertEquals(root.resolve(".zolt/build/quarkus-app"), quarkus.packageDirectory());
        assertEquals(root.resolve(".zolt/build/quarkus-app/quarkus-run.jar"), quarkus.runnerJar());
        assertEquals(root.resolve(".zolt/build/quarkus-app/quarkus/generated-bytecode.jar"), quarkus.generatedBytecodeJar());
        assertEquals(root.resolve(".zolt/build/quarkus-app/quarkus/transformed-bytecode.jar"), quarkus.transformedBytecodeJar());
        assertGeneratedOutput(
                quarkus.generatedOutputs().get(1),
                "generated-bytecode",
                "generated-bytecode-jar",
                root.resolve(".zolt/build/quarkus-app/quarkus/generated-bytecode.jar"),
                false);
        assertEquals("QUARKUS_AUGMENTATION_MISSING", diagnostics.getFirst().code());
    }

    private static void assertGeneratedOutput(
            IdeModel.QuarkusGeneratedOutput output,
            String id,
            String kind,
            Path path,
            boolean exists) {
        assertEquals(id, output.id());
        assertEquals(kind, output.kind());
        assertEquals(path, output.path());
        assertEquals(exists, output.exists());
    }

    private static void writeGeneratedOutput(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "generated\n");
    }

    private ProjectConfig quarkusProject(Path projectDir) throws IOException {
        return quarkusProject(projectDir, null);
    }

    private ProjectConfig plainProject(Path projectDir) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "plain"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        return new ManifestProjectConfigLoader().load(projectDir.resolve("zolt.toml"));
    }

    private ProjectConfig quarkusProject(Path projectDir, String outputRoot) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "quarkus"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [package]
                mode = "quarkus"
                """
                + (outputRoot == null ? "" : """

                [build.output]
                root = "%s"
                """.formatted(outputRoot)));
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        return new ManifestProjectConfigLoader().load(projectDir.resolve("zolt.toml"));
    }

    private IdeModel modelWith(IdeModel.FrameworkInfo frameworks, List<IdeModel.Diagnostic> diagnostics) {
        return new IdeModel(
                1,
                new IdeModel.ProjectInfo("quarkus", "com.example", "0.1.0", null),
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
                new IdeModel.ClasspathInfo(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
                frameworks,
                diagnostics);
    }
}
