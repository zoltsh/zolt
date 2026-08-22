package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * Design §6.9/§9.8: a workspace has exactly one authoritative lockfile at its root. A command run in
 * a member directory therefore plans against the workspace-root {@code zolt.lock} and never against a
 * member-local file, which is not part of the language at all.
 */
final class PlanCommandMemberLockTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberPlanReadsWorkspaceRootLock() throws IOException {
        Path member = workspace();
        Files.writeString(member.getParent().getParent().resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute("plan", "--cwd", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("- lockfile [resolve] ready"), result.stdout());
        assertFalse(result.stdout().contains("missing-lockfile"), result.stdout());
    }

    @Test
    void memberPlanIgnoresMemberLocalLock() throws IOException {
        Path member = workspace();
        Files.writeString(member.resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute("plan", "--cwd", member.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("blocker missing-lockfile"), result.stdout());
    }

    @Test
    void memberPlanReportsMissingWorkspaceRootLock() throws IOException {
        Path member = workspace();

        CommandResult result = execute("plan", "--cwd", member.toString(), "--format", "json");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("\"code\": \"missing-lockfile\""), result.stdout());
        assertTrue(result.stdout().contains("\"workspaceLockfile\": true"), result.stdout());
        String rootLock = member.getParent().getParent().resolve("zolt.lock").toString();
        assertTrue(
                result.stdout().contains("\"lockfilePath\": \"" + rootLock.replace("\\", "\\\\") + "\""),
                result.stdout());
        assertFalse(result.stdout().contains("../.."), result.stdout());
    }

    @Test
    void memberNativePlanUsesWorkspaceRootLock() throws IOException {
        Path member = workspace("""
                [project]
                name = "api"
                main = "com.example.Main"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "3.3.6"

                [dependencies]
                "org.springframework.boot:spring-boot-starter-web" = "3.3.6"

                [framework.spring-boot]
                native = true

                [native]
                name = "api"
                """);
        Files.writeString(member.getParent().getParent().resolve("zolt.lock"), "version = 7\n");

        CommandResult result = execute(
                "plan", "--cwd", member.toString(), "--target", "native", "--format", "json");

        assertTrue(
                result.stdout().contains("\"code\": \"missing-spring-aot-tooling\""),
                () -> "the workspace-root lock must be the one read: " + result.stdout()
                        + result.stderr());
        assertFalse(result.stdout().contains("\"code\": \"missing-lockfile\""), result.stdout());
        String rootLock = member.getParent().getParent().resolve("zolt.lock").toString();
        assertTrue(
                result.stdout().contains("\"lockfilePath\": \"" + rootLock.replace("\\", "\\\\") + "\""),
                result.stdout());
    }

    private Path workspace() throws IOException {
        return workspace("""
                [project]
                name = "api"
                """);
    }

    private Path workspace(String memberSource) throws IOException {
        Path root = Files.createTempDirectory(tempDir, "workspace-");
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """);
        Path member = root.resolve("apps/api");
        Files.createDirectories(member.resolve("src/main/java"));
        Files.writeString(member.resolve("zolt.toml"), memberSource);
        return member;
    }
}
