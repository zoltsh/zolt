package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.lockfile.toml.AtomicLockfileWriter;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.WorkspaceMutationLock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManifestEditTransactionRecoveryTest {
    private static final ManifestMutationServices MANIFESTS = new ManifestMutationServices();

    @TempDir
    private Path tempDir;

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

                [workspace.members]
                include = ["member"]
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
                        MANIFESTS,
                        new sh.zolt.resolve.ResolveService(),
                        config -> AuthoredManifestMutator.setVersionAlias(
                                config, new LocalId("added"), new VersionAliasValue("1.0.0")),
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

    /**
     * A concurrent manifest edit during the network resolve aborts the transaction while the journal
     * is still in STAGING. Nothing was written, so the journal must not survive: a journal that no
     * live content can match would make every later mutation command fail until the user deleted
     * {@code .zolt/manifest-edits/project} by hand.
     */
    @Test
    void concurrentManifestEditDuringResolveLeavesNoJournalBehind() throws IOException {
        writeProject(tempDir, "staging-abort");
        Path manifest = tempDir.resolve("zolt.toml");
        String concurrent = Files.readString(manifest) + "\n[versions]\nmanual = \"3.0.0\"\n";

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestEditTransaction.execute(
                        tempDir,
                        tempDir.resolve("cache"),
                        false,
                        MANIFESTS,
                        new ResolveService(),
                        config -> AuthoredManifestMutator.setVersionAlias(
                                config, new LocalId("added"), new VersionAliasValue("1.0.0")),
                        () -> writeUnchecked(manifest, concurrent)));

        assertTrue(
                failure.getMessage().contains("No changes were written"),
                failure.getMessage());
        assertEquals(concurrent, Files.readString(manifest));
        assertFalse(Files.exists(transactionDirectory()));

        // The next mutation must succeed instead of tripping over a surviving journal.
        ManifestEditResult retry = ManifestEditTransaction.execute(
                tempDir,
                tempDir.resolve("cache"),
                true,
                MANIFESTS,
                null,
                config -> AuthoredManifestMutator.setVersionAlias(
                        config, new LocalId("added"), new VersionAliasValue("1.0.0")));

        assertTrue(retry.manifestChanged());
        assertTrue(Files.readString(manifest).contains("added"));
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
                MANIFESTS,
                null,
                new ScopeExpectation(manifest, lockfile),
                config -> AuthoredManifestMutator.setVersionAlias(
                        config, new LocalId("added"), new VersionAliasValue("1.0.0")));

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
                        MANIFESTS,
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

                [workspace.members]
                include = ["apps/api", "modules/core"]
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
                        MANIFESTS,
                        resolveService,
                        config -> AuthoredManifestMutator.setVersionAlias(
                                config, new LocalId("selected"), new VersionAliasValue("1.1.0")),
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
                java = 21
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
                            MANIFESTS,
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
        return tempDir.resolve(".zolt").resolve("manifest-edits").resolve("project");
    }
}
