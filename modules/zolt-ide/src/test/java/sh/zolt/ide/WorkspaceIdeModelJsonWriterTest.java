package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceIdeModelJsonWriterTest {
    private final WorkspaceIdeModelService service = new WorkspaceIdeModelService();

    @TempDir
    private Path tempDir;

    @Test
    void writerPrintsWorkspaceModelWithNestedProjectModels() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api");
        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        String json = new WorkspaceIdeModelJsonWriter().write(model);

        assertTrue(json.contains("\"workspace\": {"));
        assertTrue(json.contains("\"members\": ["));
        assertTrue(json.contains("\"buildOrder\": ["));
        assertTrue(json.contains("\"apps/api\""));
        assertTrue(json.contains("\"projects\": ["));
        assertTrue(json.contains("\"member\": \"apps/api\""));
        assertTrue(json.contains("\"model\": {"));
        assertTrue(json.contains("\"name\": \"api\""));
        assertTrue(json.contains("\"edges\": []"));
        assertTrue(json.contains("\"diagnostics\": []"));
    }

    @Test
    void writerPrintsWorkspaceProjectEdges() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        member("modules/core", "core");
        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        String json = new WorkspaceIdeModelJsonWriter().write(model);

        assertTrue(json.contains("\"edges\": ["));
        assertTrue(json.contains("\"from\": \"apps/api\""));
        assertTrue(json.contains("\"to\": \"modules/core\""));
        assertTrue(json.contains("\"scope\": \"compile\""));
        assertTrue(json.contains("\"coordinate\": \"com.acme:core\""));
        assertTrue(json.contains("\"exported\": false"));
    }

    @Test
    void exportsWorkspaceApiEdgesAsExported() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                """);
        member("apps/api", "api", """

                [api.dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        member("modules/core", "core");

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        assertTrue(
                model.edges().equals(
                        java.util.List.of(new WorkspaceIdeModel.ProjectEdge("apps/api", "modules/core", "compile", "com.acme:core", true))));

        String json = new WorkspaceIdeModelJsonWriter().write(model);
        assertTrue(json.contains("\"coordinate\": \"com.acme:core\""));
        assertTrue(json.contains("\"exported\": true"));
    }

    @Test
    void writerEscapesWorkspaceDiagnosticsAndNullWorkspaceFields() {
        WorkspaceIdeModel model = new WorkspaceIdeModel(
                1,
                new WorkspaceIdeModel.WorkspaceInfo(null, null, null, null, List.of(), List.of(), List.of()),
                List.of(),
                List.of(),
                List.of(new IdeModel.Diagnostic(
                        "error",
                        "WORKSPACE_INVALID",
                        "bad \"workspace\"\npath\t\u001f",
                        Path.of("C:\\repo\\zolt.toml"),
                        "Fix \"workspace\" and retry.")));

        String json = new WorkspaceIdeModelJsonWriter().write(model);

        assertTrue(json.contains("\"name\": null"));
        assertTrue(json.contains("\"root\": null"));
        assertTrue(json.contains("\"message\": \"bad \\\"workspace\\\"\\npath\\t\\u001f\""));
        assertTrue(json.contains("\"path\": \"C:/repo/zolt.toml\""));
        assertTrue(json.contains("\"nextStep\": \"Fix \\\"workspace\\\" and retry.\""));
    }

    @Test
    void writerPrintsMultipleEdgesAndDiagnosticsDeterministically() {
        WorkspaceIdeModel model = new WorkspaceIdeModel(
                1,
                new WorkspaceIdeModel.WorkspaceInfo(
                        "ordered-workspace",
                        Path.of("C:\\repo"),
                        Path.of("C:\\repo\\zolt-workspace.toml"),
                        Path.of("C:\\repo\\zolt.lock"),
                        List.of("apps/api", "modules/core"),
                        List.of("apps/api"),
                        List.of("modules/core", "apps/api")),
                List.of(),
                List.of(
                        new WorkspaceIdeModel.ProjectEdge("apps/api", "modules/core", "compile", "com.acme:core", true),
                        new WorkspaceIdeModel.ProjectEdge("apps/api", "modules/core", "test", "com.acme:core", false)),
                List.of(
                        new IdeModel.Diagnostic(
                                "warning",
                                "FIRST",
                                "first \b\f\r",
                                Path.of("C:\\repo\\zolt.lock"),
                                "Retry first."),
                        new IdeModel.Diagnostic(
                                "error",
                                "SECOND",
                                "second",
                                Path.of("C:\\repo\\zolt-workspace.toml"),
                                "Retry second.")));

        String json = new WorkspaceIdeModelJsonWriter().write(model);

        assertTrue(json.contains("\"root\": \"C:/repo\""));
        assertTrue(json.indexOf("\"scope\": \"compile\"") < json.indexOf("\"scope\": \"test\""));
        assertTrue(json.contains("\"exported\": true"));
        assertTrue(json.contains("\"exported\": false"));
        assertTrue(json.indexOf("\"code\": \"FIRST\"") < json.indexOf("\"code\": \"SECOND\""));
        assertTrue(json.contains("\"message\": \"first \\b\\f\\r\""));
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 5\n");
    }

    private void member(String path, String name) throws IOException {
        member(path, name, "");
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "21"
                %s""".formatted(name, extraToml));
        Files.writeString(member.resolve("zolt.lock"), "version = 5\n");
    }
}
