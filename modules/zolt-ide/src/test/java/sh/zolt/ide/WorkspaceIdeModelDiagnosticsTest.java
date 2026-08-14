package sh.zolt.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceIdeModelDiagnosticsTest {
    private final WorkspaceIdeModelService service = new WorkspaceIdeModelService();

    @TempDir
    private Path tempDir;

    @Test
    void omitsUnsafeMemberOutputsFromWorkspaceClasspaths() throws IOException {
        workspace("""
                [workspace]
                name = "bad"
                members = ["apps/api"]
                """);
        member("apps/api", "api", """

                [build]
                output = "../classes"
                testOutput = "../test-classes"
                """);

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        IdeModel apiModel = model.projects().getFirst().model();
        assertTrue(apiModel.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("PROJECT_PATH_INVALID")
                        && diagnostic.message().contains("[build].output")));
        assertTrue(apiModel.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("PROJECT_PATH_INVALID")
                        && diagnostic.message().contains("[build].testOutput")));
        assertTrue(apiModel.classpaths().runtime().isEmpty());
        assertTrue(apiModel.classpaths().test().isEmpty());
    }

    @Test
    void reportsMissingWorkspaceLockAtWorkspaceLevel() throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api");

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        assertEquals("LOCKFILE_MISSING", model.diagnostics().getFirst().code());
        assertEquals(tempDir.resolve("zolt.lock").toAbsolutePath().normalize(), model.diagnostics().getFirst().path());
        assertEquals(java.util.List.of(), model.projects().getFirst().model().diagnostics());
        assertEquals(tempDir.resolve("zolt.lock").toAbsolutePath().normalize(), model.projects().getFirst().model().paths().lockfile());
    }

    @Test
    void reportsUnreadableWorkspaceLockAtWorkspaceLevel() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api");
        Files.writeString(tempDir.resolve("zolt.lock"), "version = \"one\"\n");

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_UNREADABLE", diagnostic.code());
        assertEquals(tempDir.resolve("zolt.lock").toAbsolutePath().normalize(), diagnostic.path());
        assertEquals("Run zolt resolve --workspace.", diagnostic.nextStep());
        assertEquals(java.util.List.of(), model.projects().getFirst().model().classpaths().compile());
    }

    @Test
    void checkLockReportsStaleWorkspaceLockWithoutRefreshingIt() throws IOException {
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
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 6\n");

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), true, true);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_STALE", diagnostic.code());
        assertTrue(diagnostic.message().contains("Workspace zolt.lock is out of date."));
        assertEquals(tempDir.resolve("zolt.lock").toAbsolutePath().normalize(), diagnostic.path());
        assertEquals("Run zolt resolve --workspace.", diagnostic.nextStep());
        assertEquals("version = 6\n", Files.readString(tempDir.resolve("zolt.lock")));
    }

    @Test
    void checkLockOfflineReportsUnavailableWorkspaceCacheWithoutRefreshingLockfile() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.example:missing" = "1.0.0"
                """);
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 6\n");

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), true, true);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_CHECK_UNAVAILABLE", diagnostic.code());
        assertTrue(diagnostic.message().contains("Offline mode requires cached POM"));
        assertEquals(tempDir.resolve("zolt.lock").toAbsolutePath().normalize(), diagnostic.path());
        assertEquals(
                "Run zolt resolve --workspace without --offline to seed the cache, then retry zolt ide model --workspace --offline.",
                diagnostic.nextStep());
        assertEquals("version = 6\n", Files.readString(tempDir.resolve("zolt.lock")));
    }

    @Test
    void checkLockReportsLegacyWorkspaceMigrationWithoutRefreshingLockfile() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api");
        Files.writeString(tempDir.resolve("zolt.lock"), "version = 5\n");

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), true, true);

        IdeModel.Diagnostic diagnostic = model.diagnostics().getFirst();
        assertEquals("LOCKFILE_MIGRATION_REQUIRED", diagnostic.code());
        assertTrue(diagnostic.message().contains("version 5 predates the version 6"));
        assertEquals("Run zolt resolve --workspace.", diagnostic.nextStep());
        assertEquals("version = 5\n", Files.readString(tempDir.resolve("zolt.lock")));
    }

    @Test
    void reportsMissingWorkspaceAsStructuredDiagnostic() throws IOException {
        Files.createDirectories(tempDir.resolve("standalone"));

        WorkspaceIdeModel model = service.export(tempDir.resolve("standalone"), tempDir.resolve("cache"), false, false);

        assertEquals("WORKSPACE_NOT_FOUND", model.diagnostics().getFirst().code());
        assertEquals("Could not find workspace config.", model.diagnostics().getFirst().message());
        assertTrue(model.projects().isEmpty());
    }

    @Test
    void reportsInvalidWorkspaceAsStructuredDiagnostic() throws IOException {
        workspace("""
                [workspace]
                name = "bad"
                members = ["../outside"]
                """);

        WorkspaceIdeModel model = service.export(tempDir, tempDir.resolve("cache"), false, false);

        assertEquals("WORKSPACE_INVALID", model.diagnostics().getFirst().code());
        assertTrue(model.diagnostics().getFirst().message().contains("Invalid workspace member path"));
        assertTrue(model.projects().isEmpty());
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
