package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IdeModelJsonWriterTest {
    @TempDir
    private Path tempDir;

    @Test
    void writerEscapesSharedJsonStringFieldsAndNormalizesBackslashPaths() {
        String message = "quote \" slash \\ backspace \b formfeed \f newline\n carriage\r tab\t control "
                + (char) 0x1f;
        IdeModel model = modelWith(
                disabledFrameworks(),
                List.of(new IdeModel.Diagnostic(
                        "error",
                        "JSON_ESCAPE",
                        message,
                        Path.of("C:\\repo\\zolt.toml"),
                        "Fix \"zolt.toml\" and retry.")));

        String json = new IdeModelJsonWriter().write(model);

        String escapedControl = "\\" + "u001f";
        String expectedMessage = "\"message\": \"quote \\\" slash \\\\ backspace \\b formfeed \\f newline\\n carriage\\r tab\\t control "
                + escapedControl
                + "\"";
        assertTrue(json.contains(expectedMessage));
        assertTrue(json.contains("\"path\": \"C:/repo/zolt.toml\""));
        assertTrue(json.contains("\"nextStep\": \"Fix \\\"zolt.toml\\\" and retry.\""));
    }

    @Test
    void writerPrintsQuarkusGeneratedOutputsAndDeploymentClasspath() {
        IdeModel.FrameworkInfo frameworks = new IdeModel.FrameworkInfo(new IdeModel.QuarkusInfo(
                true,
                "fast-jar",
                "fresh",
                "input-sha",
                "input-sha",
                Path.of("target/quarkus/augmentation.json"),
                Path.of("target/quarkus/augmentation"),
                Path.of("target/quarkus/package"),
                Path.of("target/quarkus/app-runner.jar"),
                Path.of("target/quarkus/generated-bytecode.jar"),
                Path.of("target/quarkus/transformed-bytecode.jar"),
                List.of(
                        new IdeModel.QuarkusGeneratedOutput(
                                "generated-rest",
                                "source",
                                Path.of("C:\\project\\target\\quarkus\\generated-rest"),
                                true),
                        new IdeModel.QuarkusGeneratedOutput(
                                "generated-bytecode",
                                "bytecode",
                                Path.of("C:\\project\\target\\quarkus\\generated-bytecode"),
                                false)),
                List.of(Path.of("C:\\cache\\io\\quarkus\\quarkus-rest-deployment.jar"))));

        String json = new IdeModelJsonWriter().write(modelWith(frameworks, List.of()));

        assertTrue(json.contains("\"quarkus\": {"));
        assertTrue(json.contains("\"generatedOutputs\": ["));
        assertTrue(json.contains("\"id\": \"generated-rest\""));
        assertTrue(json.contains("\"kind\": \"source\""));
        assertTrue(json.contains("\"path\": \"C:/project/target/quarkus/generated-rest\""));
        assertTrue(json.contains("\"exists\": true"));
        assertTrue(json.contains("\"id\": \"generated-bytecode\""));
        assertTrue(json.contains("\"exists\": false"));
        assertTrue(json.contains("\"deploymentClasspath\": ["));
        assertTrue(json.contains("\"C:/cache/io/quarkus/quarkus-rest-deployment.jar\""));
    }

    private IdeModel modelWith(
            IdeModel.FrameworkInfo frameworks,
            List<IdeModel.Diagnostic> diagnostics) {
        return new IdeModel(
                1,
                new IdeModel.ProjectInfo("json-writer", "com.example", "0.1.0", null),
                new IdeModel.JavaInfo("21", null, null),
                new IdeModel.CompilerInfo(null, "21", null, List.of(), List.of(), null, null),
                new IdeModel.TestRuntimeInfo(List.of(), Map.of(), Map.of(), List.of()),
                new IdeModel.PackageInfo(
                        "jar",
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

    private static IdeModel.FrameworkInfo disabledFrameworks() {
        return new IdeModel.FrameworkInfo(new IdeModel.QuarkusInfo(
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
                List.of()));
    }
}
