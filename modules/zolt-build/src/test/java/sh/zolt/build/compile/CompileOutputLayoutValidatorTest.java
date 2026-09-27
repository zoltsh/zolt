package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.build.BuildException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProtobufGenerationSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CompileOutputLayoutValidatorTest {
    @TempDir
    private Path projectDir;

    @Test
    void generatedOutputCannotContainMainSourceRoot() throws IOException {
        Files.createDirectories(projectDir.resolve("src/main/java"));
        ProjectConfig config = config(
                BuildSettings.defaults(),
                new CompilerSettings("src", "target/generated/test-sources/annotations"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(projectDir, config));

        assertTrue(exception.getMessage().contains("[compiler.generated].main"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
    }

    @Test
    void testOutputCannotOverlapMainOutput() throws IOException {
        Files.createDirectories(projectDir.resolve("target/classes"));
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "target/classes",
                "target/classes/tests");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateTest(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("overlaps another compile scope"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build.output].main"), exception.getMessage());
    }

    @Test
    void symlinkAliasToSourceRootIsRejected() throws IOException {
        Path sourceRoot = projectDir.resolve("src/main/java");
        Files.createDirectories(sourceRoot);
        Path outputAlias = projectDir.resolve("classes-link");
        try {
            Files.createSymbolicLink(outputAlias, sourceRoot);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "classes-link",
                "target/test-classes");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("contains protected project input"), exception.getMessage());
    }

    @Test
    void projectRootSourceMayContainConventionalOutput() {
        BuildSettings build = new BuildSettings(
                ".",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes");

        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateMain(
                projectDir, config(build, CompilerSettings.defaults())));
    }

    @Test
    void postCompileClassInputDoesNotMakeFreshCompileOutputUnsafe() {
        BuildSettings build = BuildSettings.defaults().withGeneratedSources(
                List.of(projectExecStep(List.of("target/classes"))),
                List.of());

        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateMain(
                projectDir, config(build, CompilerSettings.defaults())));
    }

    @Test
    void postCompileNonClassInputRemainsProtected() {
        BuildSettings build = new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        "target",
                        "out/main",
                        "out/test")
                .withGeneratedSources(
                        List.of(projectExecStep(List.of("out/main", "config/template.txt"))),
                        List.of());
        CompilerSettings compiler = new CompilerSettings("config", "target/generated/test-sources/annotations");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(build, compiler)));

        assertTrue(exception.getMessage().contains("[generated.main.post].inputs[1]"), exception.getMessage());
    }

    @Test
    void projectRunnerMayConsumeCustomMainOutput() {
        BuildSettings build = new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        "target",
                        "out/main",
                        "out/test")
                .withGeneratedSources(
                        List.of(projectExecStep(List.of("out/main"))),
                        List.of());

        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateMain(
                projectDir, config(build, CompilerSettings.defaults())));
    }

    @Test
    void openApiConfigAndTemplateInputsAreProtected() {
        GeneratedSourceStep step = openApiStep("config/openapi.json", "templates");
        BuildSettings configOutput = new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        "target",
                        "config",
                        "target/test-classes")
                .withGeneratedSources(List.of(step), List.of());
        BuildSettings templateOutput = new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        "target",
                        "templates",
                        "target/test-classes")
                .withGeneratedSources(List.of(step), List.of());

        BuildException configException = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(configOutput, CompilerSettings.defaults())));
        BuildException templateException = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(templateOutput, CompilerSettings.defaults())));

        assertTrue(configException.getMessage().contains("[generated.main.api].config"), configException.getMessage());
        assertTrue(
                templateException.getMessage().contains("[generated.main.api].templateDir"),
                templateException.getMessage());
    }

    @Test
    void mainOutputCannotOverlapIntegrationTestOutput() {
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "target/integration-test-classes",
                "target/test-classes");

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("[build.output].integration"), exception.getMessage());
    }

    @Test
    void projectedIntegrationScopeMayUseItsOwnOutput() {
        BuildSettings build = BuildSettings.defaults().asIntegrationTestBuild();

        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateTest(
                projectDir, config(build, CompilerSettings.defaults())));
    }

    @Test
    void originalIntegrationSettingsRejectOverlapBeforeProjection() {
        BuildSettings original = BuildSettings.defaults().withIntegrationTestSettings(
                "target/test-classes/integration",
                List.of("src/integration-test/java"),
                List.of("src/integration-test/resources"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateTest(
                        projectDir, config(original, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("[build.output].integration"), exception.getMessage());
    }

    @Test
    void distinctIntegrationSettingsAndTheirProjectionAreSafe() {
        BuildSettings original = BuildSettings.defaults();

        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateTest(
                projectDir, config(original, CompilerSettings.defaults())));
        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateTest(
                projectDir,
                config(original.asIntegrationTestBuild(), CompilerSettings.defaults())));
    }

    private static GeneratedSourceStep projectExecStep(List<String> inputs) {
        ExecGenerationSettings exec = new ExecGenerationSettings(
                "project",
                ExecToolSettings.project("p.Generator"),
                List.of(),
                ProducesLane.RESOURCES,
                Optional.empty(),
                Map.of(),
                "content");
        return new GeneratedSourceStep(
                "post",
                GeneratedSourceKind.EXEC,
                "java",
                "target/generated/resources/post",
                inputs,
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                exec);
    }

    private static GeneratedSourceStep openApiStep(String config, String templateDir) {
        OpenApiGenerationSettings openApi = new OpenApiGenerationSettings(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(config),
                Optional.of(templateDir),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
        return new GeneratedSourceStep(
                "api",
                GeneratedSourceKind.OPENAPI,
                "java",
                "target/generated/sources/openapi",
                List.of("spec/api.yaml"),
                true,
                true,
                openApi);
    }

    private static ProjectConfig config(BuildSettings build, CompilerSettings compiler) {
        return ProjectConfigs.withDependencySections(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "p",
                        currentJavaMajorVersion(),
                        Optional.of("p.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                build,
                NativeSettings.defaults(),
                compiler);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
