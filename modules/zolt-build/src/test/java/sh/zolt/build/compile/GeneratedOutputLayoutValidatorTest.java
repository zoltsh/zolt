package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.project.BuildMetadataSettings;
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

final class GeneratedOutputLayoutValidatorTest {
    @TempDir
    private Path projectDir;

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
                () -> CompileOutputLayoutValidator.validateMain(projectDir, config(build, compiler)));

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
    void openApiOutputCannotBeNestedInsideMainSourceRoot() throws IOException {
        Path source = projectDir.resolve("src/main/java/generated/p/Important.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; public final class Important {}\n");
        GeneratedSourceStep step = openApiStep(
                "config/openapi.json",
                "templates",
                "src/main/java/generated");
        BuildSettings build = BuildSettings.defaults().withGeneratedSources(List.of(step), List.of());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("[generated.main.api].output"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
        assertEquals("package p; public final class Important {}\n", Files.readString(source));
    }

    @Test
    void protobufOutputCannotBeNestedInsideMainResourceRoot() throws IOException {
        Path resource = projectDir.resolve("src/main/resources/generated/important.properties");
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, "important=true\n");
        GeneratedSourceStep step = new GeneratedSourceStep(
                "proto",
                GeneratedSourceKind.PROTOBUF,
                "java",
                "src/main/resources/generated",
                List.of("src/main/proto/api.proto"),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty());
        BuildSettings build = buildWithResources().withGeneratedSources(List.of(step), List.of());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("[generated.main.proto].output"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[resources].main[0]"), exception.getMessage());
        assertEquals("important=true\n", Files.readString(resource));
    }

    @Test
    void testGeneratorOutputCannotBeNestedInsideTestSourceRoot() throws IOException {
        Path source = projectDir.resolve("src/test/java/generated/p/ImportantTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; public final class ImportantTest {}\n");
        GeneratedSourceStep step = openApiStep(
                "config/openapi.json",
                "templates",
                "src/test/java/generated");
        BuildSettings build = BuildSettings.defaults().withGeneratedSources(List.of(), List.of(step));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateTest(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("[generated.test.api].output"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[test.sources].java[0]"), exception.getMessage());
        assertEquals("package p; public final class ImportantTest {}\n", Files.readString(source));
    }

    @Test
    void postCompileExecOutputCannotOverlapCompiledClasses() {
        BuildSettings build = BuildSettings.defaults().withGeneratedSources(
                List.of(projectExecStep("target/classes/generated", List.of("target/classes"))),
                List.of());

        BuildException exception = assertThrows(
                BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(
                        projectDir, config(build, CompilerSettings.defaults())));

        assertTrue(exception.getMessage().contains("[generated.main.post].output"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build.output].main"), exception.getMessage());
        assertTrue(exception.getMessage().contains("overlaps another owned output"), exception.getMessage());
    }

    private static GeneratedSourceStep projectExecStep(List<String> inputs) {
        return projectExecStep("target/generated/resources/post", inputs);
    }

    private static GeneratedSourceStep projectExecStep(String output, List<String> inputs) {
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
                output,
                inputs,
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                exec);
    }

    private static GeneratedSourceStep openApiStep(String config, String templateDir) {
        return openApiStep(config, templateDir, "target/generated/sources/openapi");
    }

    private static GeneratedSourceStep openApiStep(
            String config,
            String templateDir,
            String output) {
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
                output,
                List.of("spec/api.yaml"),
                true,
                true,
                openApi);
    }

    private static BuildSettings buildWithResources() {
        return new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                List.of(),
                List.of("src/main/resources"),
                List.of("src/test/resources"),
                BuildMetadataSettings.defaults());
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
