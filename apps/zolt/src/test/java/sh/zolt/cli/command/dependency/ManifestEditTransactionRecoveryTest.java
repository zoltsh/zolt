package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.workspace.service.WorkspaceMutationLock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManifestEditTransactionRecoveryTest {
    @TempDir
    private Path tempDir;

    @Test
    void interruptedCommitRestoresBothBackups() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction);
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"partial\"\n");
        Files.writeString(tempDir.resolve("zolt.lock"), "lock = \"partial\"\n");
        Files.writeString(transaction.resolve("zolt.toml.backup"), "manifest = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.toml.staged"), "manifest = \"partial\"\n");
        Files.writeString(transaction.resolve("zolt.lock.backup"), "lock = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.lock.staged"), "lock = \"partial\"\n");
        Files.writeString(transaction.resolve("state"), "MANIFEST_COMMITTED\n");

        ManifestEditTransaction.recover(transaction, tempDir);

        assertEquals("manifest = \"original\"\n", Files.readString(tempDir.resolve("zolt.toml")));
        assertEquals("lock = \"original\"\n", Files.readString(tempDir.resolve("zolt.lock")));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void completedCommitIsKeptAndOnlyJournalIsCleaned() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction);
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"committed\"\n");
        Files.writeString(tempDir.resolve("zolt.lock"), "lock = \"committed\"\n");
        Files.writeString(transaction.resolve("zolt.toml.backup"), "manifest = \"old\"\n");
        Files.writeString(transaction.resolve("zolt.toml.staged"), "manifest = \"committed\"\n");
        Files.writeString(transaction.resolve("zolt.lock.backup"), "lock = \"old\"\n");
        Files.writeString(transaction.resolve("state"), "COMMITTED\n");

        ManifestEditTransaction.recover(transaction, tempDir);

        assertEquals("manifest = \"committed\"\n", Files.readString(tempDir.resolve("zolt.toml")));
        assertEquals("lock = \"committed\"\n", Files.readString(tempDir.resolve("zolt.lock")));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void preCommitJournalStatesRestoreOriginalFiles() throws IOException {
        for (String state : new String[] {"STAGING", "PREPARED"}) {
            Path root = tempDir.resolve(state.toLowerCase());
            Path transaction = root.resolve(".zolt/manifest-edit-transaction");
            Files.createDirectories(transaction);
            Files.writeString(root.resolve("zolt.toml"), "manifest = \"original\"\n");
            Files.writeString(root.resolve("zolt.lock"), "lock = \"original\"\n");
            Files.writeString(transaction.resolve("zolt.toml.backup"), "manifest = \"original\"\n");
            Files.writeString(transaction.resolve("zolt.toml.staged"), "manifest = \"edited\"\n");
            Files.writeString(transaction.resolve("zolt.lock.backup"), "lock = \"original\"\n");
            Files.writeString(transaction.resolve("zolt.lock.staged"), "lock = \"edited\"\n");
            Files.writeString(transaction.resolve("state"), state + "\n");

            ManifestEditTransaction.recover(transaction, root);

            assertEquals("manifest = \"original\"\n", Files.readString(root.resolve("zolt.toml")));
            assertEquals("lock = \"original\"\n", Files.readString(root.resolve("zolt.lock")));
            assertFalse(Files.exists(transaction));
        }
    }

    @Test
    void incompleteJournalIsRemovedOnlyBeforeAnyCommitStateExists() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction.resolve("resolve"));
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"original\"\n");
        Files.writeString(transaction.resolve("resolve/partial"), "staging\n");

        ManifestEditTransaction.recover(transaction, tempDir);

        assertEquals("manifest = \"original\"\n", Files.readString(tempDir.resolve("zolt.toml")));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void recoveryRefusesToOverwriteAConcurrentManualEdit() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction);
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"manual\"\n");
        Files.writeString(tempDir.resolve("zolt.lock"), "lock = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.toml.backup"), "manifest = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.toml.staged"), "manifest = \"zolt\"\n");
        Files.writeString(transaction.resolve("zolt.lock.backup"), "lock = \"original\"\n");
        Files.writeString(transaction.resolve("state"), "MANIFEST_COMMITTED\n");

        assertThrows(
                ZoltConfigException.class,
                () -> ManifestEditTransaction.recover(transaction, tempDir));

        assertEquals("manifest = \"manual\"\n", Files.readString(tempDir.resolve("zolt.toml")));
        assertEquals("lock = \"original\"\n", Files.readString(tempDir.resolve("zolt.lock")));
        assertTrue(Files.exists(transaction));
    }

    @Test
    void interruptedFirstLockfileCommitRestoresManifestAndRemovesNewLockfile() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction);
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"edited\"\n");
        Files.writeString(tempDir.resolve("zolt.lock"), "lock = \"new\"\n");
        Files.writeString(transaction.resolve("zolt.toml.backup"), "manifest = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.toml.staged"), "manifest = \"edited\"\n");
        Files.writeString(transaction.resolve("zolt.lock.absent"), "absent\n");
        Files.writeString(transaction.resolve("zolt.lock.staged"), "lock = \"new\"\n");
        Files.writeString(transaction.resolve("state"), "MANIFEST_COMMITTED\n");

        ManifestEditTransaction.recover(transaction, tempDir);

        assertEquals("manifest = \"original\"\n", Files.readString(tempDir.resolve("zolt.toml")));
        assertFalse(Files.exists(tempDir.resolve("zolt.lock")));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void recoveryRestoresTheLockfileWhenTheManifestWasAlreadyRolledBack() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction);
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"original\"\n");
        Files.writeString(tempDir.resolve("zolt.lock"), "lock = \"edited\"\n");
        Files.writeString(transaction.resolve("zolt.toml.backup"), "manifest = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.toml.staged"), "manifest = \"edited\"\n");
        Files.writeString(transaction.resolve("zolt.lock.backup"), "lock = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.lock.staged"), "lock = \"edited\"\n");
        Files.writeString(transaction.resolve("state"), "MANIFEST_COMMITTED\n");

        ManifestEditTransaction.recover(transaction, tempDir);

        assertEquals("manifest = \"original\"\n", Files.readString(tempDir.resolve("zolt.toml")));
        assertEquals("lock = \"original\"\n", Files.readString(tempDir.resolve("zolt.lock")));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void recoveryRefusesToOverwriteAConcurrentLockfileEdit() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction);
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"edited\"\n");
        Files.writeString(tempDir.resolve("zolt.lock"), "lock = \"manual\"\n");
        Files.writeString(transaction.resolve("zolt.toml.backup"), "manifest = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.toml.staged"), "manifest = \"edited\"\n");
        Files.writeString(transaction.resolve("zolt.lock.backup"), "lock = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.lock.staged"), "lock = \"edited\"\n");
        Files.writeString(transaction.resolve("state"), "MANIFEST_COMMITTED\n");

        assertThrows(
                ZoltConfigException.class,
                () -> ManifestEditTransaction.recover(transaction, tempDir));

        assertEquals("manifest = \"edited\"\n", Files.readString(tempDir.resolve("zolt.toml")));
        assertEquals("lock = \"manual\"\n", Files.readString(tempDir.resolve("zolt.lock")));
        assertTrue(Files.exists(transaction));
    }

    @Test
    void standaloneProjectEditWaitsForItsMutationLock() throws Exception {
        writeProject(tempDir, "standalone");

        assertEditWaitsForLock(tempDir, tempDir);
    }

    @Test
    void workspaceMemberEditWaitsForTheWorkspaceMutationLock() throws Exception {
        Path member = tempDir.resolve("member");
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "locked"
                members = ["member"]
                """);
        Files.createDirectories(member);
        writeProject(member, "member");

        assertEditWaitsForLock(tempDir, member);
    }

    private static void writeProject(Path directory, String name) throws IOException {
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                """.formatted(name));
    }

    private static void assertEditWaitsForLock(Path lockRoot, Path projectRoot) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        Future<ManifestEditTransaction.Result> edit;
        try {
            try (WorkspaceMutationLock ignored = WorkspaceMutationLock.acquire(lockRoot)) {
                edit = executor.submit(() -> {
                    submitted.countDown();
                    return ManifestEditTransaction.execute(
                            projectRoot,
                            projectRoot.resolve("cache"),
                            true,
                            new ZoltTomlParser(),
                            new ZoltTomlWriter(),
                            null,
                            config -> {
                                mutationEntered.countDown();
                                return config;
                            });
                });
                assertTrue(submitted.await(5, TimeUnit.SECONDS));
                assertFalse(mutationEntered.await(250, TimeUnit.MILLISECONDS));
            }
            assertTrue(mutationEntered.await(5, TimeUnit.SECONDS));
            edit.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private Path transactionDirectory() {
        return tempDir.resolve(".zolt").resolve("manifest-edit-transaction");
    }
}
