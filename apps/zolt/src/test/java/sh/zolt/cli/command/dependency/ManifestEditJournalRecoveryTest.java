package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.toml.ZoltConfigException;

/**
 * The recovery state machine read straight from a written journal: which recorded state means the
 * manifest and zolt.lock were already changed, and which means nothing was written at all.
 */
final class ManifestEditJournalRecoveryTest {
    @TempDir
    private Path tempDir;

    @Test
    void manifestOnlyCommitRestoresTheOriginalManifest() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction);
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"partial\"\n");
        Files.writeString(tempDir.resolve("zolt.lock"), "lock = \"original\"\n");
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
    void recordedWorkspaceRootScopeRecoversLegacyManifest() throws IOException {
        Path root = tempDir.resolve("legacy-root-recovery");
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("@workspace-root:zolt-workspace.toml".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path transaction = root.resolve(".zolt/manifest-edits").resolve(encoded);
        Files.createDirectories(transaction);
        Files.writeString(root.resolve("zolt-workspace.toml"), "platform = \"edited\"\n");
        Files.writeString(root.resolve("zolt.lock"), "lock = \"original\"\n");
        Files.writeString(transaction.resolve("manifest-root"), ".\n");
        Files.writeString(transaction.resolve("manifest-path"), "zolt-workspace.toml\n");
        Files.writeString(transaction.resolve("zolt.toml.backup"), "platform = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.toml.staged"), "platform = \"edited\"\n");
        Files.writeString(transaction.resolve("zolt.lock.backup"), "lock = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.lock.staged"), "lock = \"edited\"\n");
        Files.writeString(transaction.resolve("state"), "MANIFEST_COMMITTED\n");

        ManifestEditRecovery.recoverAll(root, root);

        assertEquals("platform = \"original\"\n", Files.readString(root.resolve("zolt-workspace.toml")));
        assertEquals("lock = \"original\"\n", Files.readString(root.resolve("zolt.lock")));
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
    void fullyStagedFilesArePromotedInsteadOfRolledBack() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction);
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"edited\"\n");
        Files.writeString(tempDir.resolve("zolt.lock"), "lock = \"edited\"\n");
        Files.writeString(transaction.resolve("zolt.toml.backup"), "manifest = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.toml.staged"), "manifest = \"edited\"\n");
        Files.writeString(transaction.resolve("zolt.lock.backup"), "lock = \"original\"\n");
        Files.writeString(transaction.resolve("zolt.lock.staged"), "lock = \"edited\"\n");
        Files.writeString(transaction.resolve("state"), "MANIFEST_COMMITTED\n");

        ManifestEditTransaction.recover(transaction, tempDir);

        assertEquals("manifest = \"edited\"\n", Files.readString(tempDir.resolve("zolt.toml")));
        assertEquals("lock = \"edited\"\n", Files.readString(tempDir.resolve("zolt.lock")));
        assertFalse(Files.exists(transaction));
    }

    @Test
    void preCommitJournalStatesRestoreOriginalFiles() throws IOException {
        for (String state : new String[] {"STAGING", "PREPARED"}) {
            Path root = tempDir.resolve(state.toLowerCase());
            Path transaction = root.resolve(".zolt/manifest-edits/project");
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
    void interruptedBeforeFirstLockfileCommitRestoresManifestAndKeepsLockfileAbsent() throws IOException {
        Path transaction = transactionDirectory();
        Files.createDirectories(transaction);
        Files.writeString(tempDir.resolve("zolt.toml"), "manifest = \"edited\"\n");
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

    private Path transactionDirectory() {
        return tempDir.resolve(".zolt").resolve("manifest-edits").resolve("project");
    }
}
