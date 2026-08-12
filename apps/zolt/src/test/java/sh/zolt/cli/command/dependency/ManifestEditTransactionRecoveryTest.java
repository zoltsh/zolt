package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.toml.ZoltTomlWriter;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.WorkspaceMutationLock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManifestEditTransactionRecoveryTest {
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

    @Test
    void transactionCannotOverwriteConcurrentResolve() throws Exception {
        writeProject(tempDir, "concurrent");
        String originalManifest = Files.readString(tempDir.resolve("zolt.toml"));
        Path lockfile = tempDir.resolve("zolt.lock");
        String concurrentLock = "lock = \"concurrent resolve\"\n";

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestEditTransaction.execute(
                        tempDir,
                        tempDir.resolve("cache"),
                        false,
                        new ZoltTomlParser(),
                        new ZoltTomlWriter(),
                        new sh.zolt.resolve.ResolveService(),
                        config -> {
                            var aliases = new LinkedHashMap<>(config.versionAliases());
                            aliases.put("added", "1.0.0");
                            return config.withVersionAliases(aliases);
                        },
                        () -> {
                            try {
                                AtomicLockfileWriter.write(lockfile, concurrentLock);
                            } catch (IOException exception) {
                                throw new AssertionError(exception);
                            }
                        }));

        assertTrue(failure.getMessage().contains("zolt.lock changed while dependency resolution was in progress"));
        assertEquals(originalManifest, Files.readString(tempDir.resolve("zolt.toml")));
        assertEquals(concurrentLock, Files.readString(lockfile));
        assertFalse(Files.exists(transactionDirectory()));
    }

    @Test
    void expectedScopeIsVerifiedAndActualChangedPathsAreOrdered() throws IOException {
        writeProject(tempDir, "expected");
        Path manifest = tempDir.resolve("zolt.toml");
        Path lockfile = tempDir.resolve("zolt.lock");

        ManifestEditResult edit = ManifestEditTransaction.execute(
                tempDir,
                tempDir.resolve("cache"),
                true,
                new ZoltTomlParser(),
                new ZoltTomlWriter(),
                null,
                new ScopeExpectation(manifest, lockfile),
                config -> {
                    var aliases = new LinkedHashMap<>(config.versionAliases());
                    aliases.put("added", "1.0.0");
                    return config.withVersionAliases(aliases);
                });

        assertTrue(edit.changed());
        assertTrue(edit.manifestChanged());
        assertFalse(edit.lockfileChanged());
        assertEquals(List.of(manifest.toAbsolutePath().normalize()), edit.changedPaths());
    }

    @Test
    void scopeMismatchFailsBeforeParsingOrMutation() throws IOException {
        writeProject(tempDir, "moved");
        String original = Files.readString(tempDir.resolve("zolt.toml"));
        AtomicBoolean mutationCalled = new AtomicBoolean();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestEditTransaction.execute(
                        tempDir,
                        tempDir.resolve("cache"),
                        true,
                        new ZoltTomlParser(),
                        new ZoltTomlWriter(),
                        null,
                        new ScopeExpectation(tempDir.resolve("elsewhere/zolt.toml"), tempDir.resolve("zolt.lock")),
                        config -> {
                            mutationCalled.set(true);
                            return config;
                        }));

        assertTrue(failure.getMessage().contains("scope changed before execution"));
        assertFalse(mutationCalled.get());
        assertEquals(original, Files.readString(tempDir.resolve("zolt.toml")));
    }

    @Test
    void workspaceEditRejectsConcurrentChangeToAnotherMember() throws IOException {
        Path root = tempDir.resolve("workspace-input-race");
        Path api = root.resolve("apps/api");
        Path core = root.resolve("modules/core");
        Files.createDirectories(api);
        Files.createDirectories(core);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "race"
                members = ["apps/api", "modules/core"]
                """);
        writeProject(api, "api");
        writeProject(core, "core");
        Path apiManifest = api.resolve("zolt.toml");
        Path coreManifest = core.resolve("zolt.toml");
        Files.writeString(apiManifest, Files.readString(apiManifest) + "\n[versions]\nselected = \"1.0.0\"\n");
        String apiOriginal = Files.readString(apiManifest);
        String coreConcurrent = Files.readString(coreManifest) + "\n[versions]\nconcurrent = \"2.0.0\"\n";
        Path cache = root.resolve("cache");
        ResolveService resolveService = new ResolveService();
        new WorkspaceResolveService(resolveService).resolve(root, cache, false, false);
        String lockOriginal = Files.readString(root.resolve("zolt.lock"));

        assertThrows(
                BuildException.class,
                () -> ManifestEditTransaction.execute(
                        api,
                        cache,
                        false,
                        new ZoltTomlParser(),
                        new ZoltTomlWriter(),
                        resolveService,
                        config -> {
                            var aliases = new LinkedHashMap<>(config.versionAliases());
                            aliases.put("selected", "1.1.0");
                            return config.withVersionAliases(aliases);
                        },
                        () -> writeUnchecked(coreManifest, coreConcurrent)));

        assertEquals(apiOriginal, Files.readString(apiManifest));
        assertEquals(coreConcurrent, Files.readString(coreManifest));
        assertEquals(lockOriginal, Files.readString(root.resolve("zolt.lock")));
        Path journals = root.resolve(".zolt/manifest-edits");
        try (var entries = Files.list(journals)) {
            assertEquals(0, entries.count());
        }
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

    private static void writeUnchecked(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static void assertEditWaitsForLock(Path lockRoot, Path projectRoot) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch mutationEntered = new CountDownLatch(1);
        Future<ManifestEditResult> edit;
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
