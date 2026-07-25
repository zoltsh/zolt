package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.workspace.WorkspaceConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceDiscoveryDependencyValidationTest {
    private final WorkspaceDiscoveryService service = new WorkspaceDiscoveryService();

    @TempDir
    private Path tempDir;

    @Test
    void rejectsWorkspaceDependencyCycle() throws IOException {
        workspace("""
                [workspace]
                name = "bad"
                members = ["apps/api", "modules/core", "modules/util"]
                """);
        member("apps/api", "api", "com.acme", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        member("modules/core", "core", "com.acme", """

                [dependencies]
                "com.acme:util" = { workspace = "modules/util" }
                """);
        member("modules/util", "util", "com.acme", """

                [dependencies]
                "com.acme:api" = { workspace = "apps/api" }
                """);

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> service.load(tempDir));

        assertEquals(
                "Workspace dependency cycle detected: apps/api -> modules/core -> modules/util -> apps/api.",
                exception.getMessage());
    }

    @Test
    void rejectsWorkspaceDependencyTargetThatIsNotAMember() throws IOException {
        workspace("""
                [workspace]
                name = "bad"
                members = ["apps/api"]
                """);
        member("apps/api", "api", "com.acme", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> service.load(tempDir));

        assertEquals(
                "Workspace dependency `com.acme:core` in member `apps/api` points to `modules/core`, but that path is not listed in [workspace].members.",
                exception.getMessage());
    }

    @Test
    void rejectsWorkspaceDependencyPathThatEscapesWorkspaceRoot() throws IOException {
        workspace("""
                [workspace]
                name = "bad"
                members = ["apps/api"]
                """);
        member("apps/api", "api", "com.acme", """

                [dependencies]
                "com.acme:core" = { workspace = "../outside" }
                """);

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> service.load(tempDir));

        assertTrue(exception.getMessage().contains("[dependencies].com.acme:core.workspace"));
        assertTrue(exception.getMessage().contains("../outside"));
    }

    @Test
    void rejectsWorkspaceDependencyCoordinateMismatch() throws IOException {
        workspace("""
                [workspace]
                name = "bad"
                members = ["apps/api", "modules/core"]
                """);
        member("apps/api", "api", "com.acme", """

                [dependencies]
                "com.acme:not-core" = { workspace = "modules/core" }
                """);
        member("modules/core", "core", "com.acme");

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> service.load(tempDir));

        assertEquals(
                "Workspace dependency `com.acme:not-core` in member `apps/api` points to `modules/core`, whose project coordinate is `com.acme:core`. Update the dependency key or workspace path so they match.",
                exception.getMessage());
    }

    @Test
    void rejectsWorkspaceSelfDependency() throws IOException {
        workspace("""
                [workspace]
                name = "bad"
                members = ["apps/api"]
                """);
        member("apps/api", "api", "com.acme", """

                [dependencies]
                "com.acme:api" = { workspace = "apps/api" }
                """);

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> service.load(tempDir));

        assertEquals(
                "Workspace member `apps/api` cannot depend on itself through `com.acme:api`.",
                exception.getMessage());
    }

    @Test
    void rejectsEveryNonLibraryPackageModeAsAWorkspaceDependencyTarget() throws IOException {
        for (String mode : List.of(
                "spring-boot",
                "quarkus",
                "uber",
                "war",
                "spring-boot-war",
                "bom")) {
            Path root = tempDir.resolve(mode);
            Files.createDirectories(root);
            Files.writeString(root.resolve("zolt-workspace.toml"), """
                    [workspace]
                    name = "unsupported-provider"
                    members = ["apps/app", "modules/provider"]
                    """);
            writeMember(root, "apps/app", "app", """

                    [dependencies]
                    "com.acme:provider" = { workspace = "modules/provider" }
                    """);
            String bomSection = mode.equals("bom") ? "\n[bom]\n" : "";
            writeMember(root, "modules/provider", "provider", """

                    [package]
                    mode = "%s"
                    %s""".formatted(mode, bomSection));

            WorkspaceConfigException exception = assertThrows(
                    WorkspaceConfigException.class,
                    () -> service.load(root),
                    mode);

            assertTrue(exception.getMessage().contains("com.acme:provider"), exception.getMessage());
            assertTrue(exception.getMessage().contains("`" + mode + "`"), exception.getMessage());
            assertTrue(exception.getMessage().contains("not a reusable library artifact"), exception.getMessage());
            assertTrue(exception.getMessage().contains("package mode `thin`"), exception.getMessage());
            assertTrue(Files.notExists(root.resolve("zolt.lock")), mode);
        }
    }

    @Test
    void acceptsAThinWorkspaceDependencyProvider() throws IOException {
        workspace("""
                [workspace]
                name = "thin-provider"
                members = ["apps/app", "modules/provider"]
                """);
        member("apps/app", "app", "com.acme", """

                [dependencies]
                "com.acme:provider" = { workspace = "modules/provider" }
                """);
        member("modules/provider", "provider", "com.acme", """

                [package]
                mode = "thin"
                """);

        assertEquals(1, service.load(tempDir).edges().size());
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
    }

    private void member(String path, String name, String group) throws IOException {
        member(path, name, group, "");
    }

    private void member(String path, String name, String group, String extraToml) throws IOException {
        writeMember(tempDir, path, name, extraToml, group);
    }

    private static void writeMember(
            Path root,
            String path,
            String name,
            String extraToml) throws IOException {
        writeMember(root, path, name, extraToml, "com.acme");
    }

    private static void writeMember(
            Path root,
            String path,
            String name,
            String extraToml,
            String group) throws IOException {
        Path member = root.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "%s"
                java = "21"
                %s""".formatted(name, group, extraToml));
    }
}
