package sh.zolt.cli.dependency;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManifestMutationRecoveryCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void noOpRemoveRecoversManifestCommittedTransaction() throws IOException {
        PendingTransaction pending = pending("remove", """
                [dependencies]
                "com.example:old" = "1.0.0"
                """, "[dependencies]\n");

        var result = execute("remove", "--cwd", pending.project().toString(), "com.example:old");

        assertRecovered(result.exitCode(), result.stderr(), pending);
    }

    @Test
    void noOpPlatformRemoveRecoversPendingTransaction() throws IOException {
        PendingTransaction pending = pending("platform-remove", """
                [platforms]
                "com.example:bom" = "1.0.0"
                """, "[platforms]\n");

        var result = execute(
                "platform", "remove", "--cwd", pending.project().toString(), "com.example:bom");

        assertRecovered(result.exitCode(), result.stderr(), pending);
    }

    @Test
    void noEditUpdateRecoversPendingTransaction() throws IOException {
        PendingTransaction pending = pending("update", "# before edit\n", "# after edit\n");

        var result = execute(
                "update",
                "--offline",
                "--cwd", pending.project().toString(),
                "--cache-root", tempDir.resolve("update-cache").toString());

        assertRecovered(result.exitCode(), result.stderr(), pending);
    }

    @Test
    void dryRunUpdateStillRecoversPendingTransaction() throws IOException {
        PendingTransaction pending = pending("dry-run", "# before edit\n", "# after edit\n");

        var result = execute(
                "update",
                "--dry-run",
                "--offline",
                "--cwd", pending.project().toString(),
                "--cache-root", tempDir.resolve("dry-run-cache").toString());

        assertRecovered(result.exitCode(), result.stderr(), pending);
    }

    @Test
    void noOpInOneMemberRecoversPendingTransactionForAnotherMember() throws IOException {
        Path workspace = tempDir.resolve("workspace-recovery");
        Path first = workspace.resolve("first");
        Path second = workspace.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(workspace.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "recovery"
                members = ["first", "second"]
                """);
        String originalFirst = project("first") + "# original\n";
        String stagedFirst = project("first") + "# staged\n";
        Files.writeString(first.resolve("zolt.toml"), stagedFirst);
        Files.writeString(second.resolve("zolt.toml"), project("second"));
        String originalLock = "lock = \"original\"\n";
        String stagedLock = "lock = \"staged\"\n";
        Files.writeString(workspace.resolve("zolt.lock"), stagedLock);
        Path transaction = workspace.resolve(".zolt/manifest-edits/Zmlyc3Q");
        Files.createDirectories(transaction);
        Files.writeString(transaction.resolve("zolt.toml.backup"), originalFirst);
        Files.writeString(transaction.resolve("zolt.toml.staged"), stagedFirst);
        Files.writeString(transaction.resolve("zolt.lock.backup"), originalLock);
        Files.writeString(transaction.resolve("zolt.lock.staged"), stagedLock);
        Files.writeString(transaction.resolve("state"), "MANIFEST_COMMITTED\n");

        var result = execute("remove", "--cwd", second.toString(), "com.example:absent");

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(stagedFirst, Files.readString(first.resolve("zolt.toml")));
        assertEquals(stagedLock, Files.readString(workspace.resolve("zolt.lock")));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void unifiedRootMemberMutationUsesWorkspaceResolution() throws IOException {
        Path workspace = tempDir.resolve("unified-root-member");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), project("root") + """
                [workspace]
                name = "unified"
                members = ["."]
                """);

        var result = execute(
                "version", "set", "added", "1.0.0",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("unified-cache").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(Files.readString(workspace.resolve("zolt.toml")).contains("\"added\" = \"1.0.0\""));
        assertTrue(Files.readString(workspace.resolve("zolt.lock"))
                .contains("workspaceResolutionInputFingerprint = \"sha256:"));
        assertFalse(Files.exists(workspace.resolve(".zolt/manifest-edit-transaction")));
        assertFalse(Files.exists(workspace.resolve(".zolt/manifest-edits/Lg")));
    }

    @Test
    void workspaceRootThatIsNotAMemberCannotOverwriteTheWorkspaceLock() throws IOException {
        Path workspace = tempDir.resolve("non-member-root");
        Path child = workspace.resolve("child");
        Files.createDirectories(child);
        Files.writeString(workspace.resolve("zolt.toml"), project("root") + """
                [workspace]
                name = "root-is-not-a-member"
                members = ["child"]
                """);
        Files.writeString(child.resolve("zolt.toml"), project("child"));
        Path lockfile = workspace.resolve("zolt.lock");
        Files.writeString(lockfile, "lock = \"workspace\"\n");
        String originalManifest = Files.readString(workspace.resolve("zolt.toml"));

        var result = execute(
                "version", "set", "added", "1.0.0",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("non-member-cache").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains(
                "Manifest mutations require a standalone project or a declared workspace member"));
        assertEquals(originalManifest, Files.readString(workspace.resolve("zolt.toml")));
        assertEquals("lock = \"workspace\"\n", Files.readString(lockfile));
    }

    private PendingTransaction pending(String name, String originalTail, String stagedTail) throws IOException {
        Path project = tempDir.resolve(name);
        Path transaction = project.resolve(".zolt/manifest-edit-transaction");
        Files.createDirectories(transaction);
        String prefix = """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                """;
        String original = prefix + originalTail;
        String staged = prefix + stagedTail;
        String originalLock = "lock = \"original\"\n";
        String stagedLock = "lock = \"staged\"\n";
        Files.writeString(project.resolve("zolt.toml"), staged);
        Files.writeString(project.resolve("zolt.lock"), stagedLock);
        Files.writeString(transaction.resolve("zolt.toml.backup"), original);
        Files.writeString(transaction.resolve("zolt.toml.staged"), staged);
        Files.writeString(transaction.resolve("zolt.lock.backup"), originalLock);
        Files.writeString(transaction.resolve("zolt.lock.staged"), stagedLock);
        Files.writeString(transaction.resolve("state"), "MANIFEST_COMMITTED\n");
        return new PendingTransaction(project, transaction, staged, stagedLock);
    }

    private static String project(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                """.formatted(name);
    }

    private static void assertRecovered(int exitCode, String stderr, PendingTransaction pending) throws IOException {
        assertEquals(0, exitCode, stderr);
        assertEquals(pending.stagedManifest(), Files.readString(pending.project().resolve("zolt.toml")));
        assertEquals(pending.stagedLock(), Files.readString(pending.project().resolve("zolt.lock")));
        assertFalse(Files.exists(pending.transaction()));
    }

    private record PendingTransaction(
            Path project,
            Path transaction,
            String stagedManifest,
            String stagedLock) {
    }
}
