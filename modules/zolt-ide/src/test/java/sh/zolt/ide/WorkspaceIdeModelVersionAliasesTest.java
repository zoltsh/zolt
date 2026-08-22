package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceIdeModelVersionAliasesTest {
    private final WorkspaceIdeModelService service = new WorkspaceIdeModelService();

    @TempDir
    private Path tempDir;

    @Test
    void exportsVersionAliasesInMemberProjectModels() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api"]
                """);
        member("apps/api", "api", """

                [versions]
                guava = "33.4.8-jre"
                junit = "5.12.1"

                [dependencies]
                "com.google.guava:guava" = { versionRef = "guava" }

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = { versionRef = "junit" }
                """);

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        IdeModel apiModel = model.projects().getFirst().model();
        assertEquals(Map.of("guava", "33.4.8-jre", "junit", "5.12.1"), apiModel.dependencies().versionAliases());
        assertEquals(
                List.of(new IdeModel.DependencyDeclaration(
                        "com.google.guava:guava",
                        "33.4.8-jre",
                        "guava",
                        false,
                        null,
                        false,
                        false,
                        List.of())),
                apiModel.dependencies().implementation());
        assertEquals(
                List.of(new IdeModel.DependencyDeclaration(
                        "org.junit.jupiter:junit-jupiter",
                        "5.12.1",
                        "junit",
                        false,
                        null,
                        false,
                        false,
                        List.of())),
                apiModel.dependencies().test());

        String json = new WorkspaceIdeModelJsonWriter().write(model);
        assertTrue(json.contains("\"versionAliases\": {"));
        assertTrue(json.contains("\"guava\": \"33.4.8-jre\""));
        assertTrue(json.contains("\"versionRef\": \"guava\""));
        assertTrue(json.contains("\"versionRef\": \"junit\""));
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), content);
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 7\n");
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
                java = 21
                %s""".formatted(name, extraToml));
        Files.writeString(member.resolve("zolt.lock"), "version = 7\n");
    }
}
