package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import sh.zolt.resolve.ResolveService;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.ZoltManifestDocument;

/**
 * Design §19.3: the crash window between the live manifest replacement and the lockfile
 * replacement. A durable {@code COMMITTING} record is written before the first live-file mutation,
 * and every journal that can name a live mutation is classified from live file content rather than
 * from its label.
 */
final class ManifestEditCommitWindowTest {
    private static final ManifestMutationServices MANIFESTS = new ManifestMutationServices();

    @TempDir
    private Path tempDir;

    /**
     * {@code writeState(MANIFEST_COMMITTED)} throwing after the manifest replacement is not a
     * theoretical power-loss case: the state write is ordinary file I/O inside the compare-and-set
     * callback. The journal must already say COMMITTING when the writer runs, and the failure must
     * restore both files.
     */
    @Test
    void failureAfterManifestReplaceBeforeStateTransitionRestoresBoth() throws IOException {
        Path project = tempDir.resolve("commit-window");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), project("window"));
        Path manifest = project.resolve("zolt.toml");
        Path lockfile = project.resolve("zolt.lock");
        Path cache = tempDir.resolve("commit-window-cache");
        seedResolvedLockfile(project, cache);
        String originalManifest = Files.readString(manifest);
        String originalLock = Files.readString(lockfile);
        AtomicReference<String> stateWhenTheManifestWasReplaced = new AtomicReference<>();

        ManifestMutationScope scope = ManifestMutationScope.discover(project, project);
        ZoltManifestDocument original = MANIFESTS.document(scope.manifestPath());
        ZoltManifestDocument edited = MANIFESTS.edit(
                original,
                AuthoredManifestMutator.setVersionAlias(
                        original.authored(), new LocalId("added"), new VersionAliasValue("1.0.0")));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ManifestEditCommitter.commit(
                        scope,
                        cache,
                        false,
                        new ResolveService(),
                        MANIFESTS.standaloneConfig(edited.source()),
                        original.source(),
                        edited.source(),
                        (path, before, after) -> {
                            stateWhenTheManifestWasReplaced.set(
                                    readUnchecked(scope.transactionDirectory().resolve("state")).strip());
                            MANIFESTS.writePrepared(path, before, after);
                            throw new IllegalStateException("state transition failed after the replacement");
                        },
                        () -> {}));

        assertEquals("state transition failed after the replacement", failure.getMessage());
        assertEquals(originalManifest, Files.readString(manifest));
        assertEquals(originalLock, Files.readString(lockfile));
        assertFalse(Files.exists(scope.transactionDirectory()));
        assertEquals(
                "COMMITTING",
                stateWhenTheManifestWasReplaced.get(),
                "the journal must record COMMITTING before the first live-file mutation");
    }

    /**
     * A journal written by an earlier release stops at PREPARED in exactly this window. Its label
     * claims nothing was written, so it may only be discarded when the live manifest agrees.
     */
    @Test
    void preparedJournalWithStagedManifestRestoresOriginal() throws IOException {
        Path root = tempDir.resolve("prepared-staged");
        Path journal = journal(root);
        Files.createDirectories(journal);
        Files.writeString(root.resolve("zolt.toml"), "manifest = \"staged\"\n");
        Files.writeString(root.resolve("zolt.lock"), "lock = \"original\"\n");
        writeJournalContents(journal);
        Files.writeString(journal.resolve("state"), "PREPARED\n");

        ManifestEditTransaction.recover(journal, root);

        assertEquals("manifest = \"original\"\n", Files.readString(root.resolve("zolt.toml")));
        assertEquals("lock = \"original\"\n", Files.readString(root.resolve("zolt.lock")));
        assertFalse(Files.exists(journal));
    }

    @Test
    void committingJournalWithOriginalFilesCleansUp() throws IOException {
        Path root = tempDir.resolve("committing-original");
        Path journal = journal(root);
        Files.createDirectories(journal);
        Files.writeString(root.resolve("zolt.toml"), "manifest = \"original\"\n");
        Files.writeString(root.resolve("zolt.lock"), "lock = \"original\"\n");
        writeJournalContents(journal);
        Files.writeString(journal.resolve("state"), "COMMITTING\n");

        ManifestEditTransaction.recover(journal, root);

        assertEquals("manifest = \"original\"\n", Files.readString(root.resolve("zolt.toml")));
        assertEquals("lock = \"original\"\n", Files.readString(root.resolve("zolt.lock")));
        assertFalse(Files.exists(journal));
    }

    @Test
    void committingJournalWithStagedFilesCompletesCommit() throws IOException {
        Path root = tempDir.resolve("committing-staged");
        Path journal = journal(root);
        Files.createDirectories(journal);
        Files.writeString(root.resolve("zolt.toml"), "manifest = \"staged\"\n");
        Files.writeString(root.resolve("zolt.lock"), "lock = \"staged\"\n");
        writeJournalContents(journal);
        Files.writeString(journal.resolve("state"), "COMMITTING\n");

        ManifestEditTransaction.recover(journal, root);

        assertEquals("manifest = \"staged\"\n", Files.readString(root.resolve("zolt.toml")));
        assertEquals("lock = \"staged\"\n", Files.readString(root.resolve("zolt.lock")));
        assertFalse(Files.exists(journal));
    }

    /**
     * The process-termination half of the same window, recovered by the next mutation command
     * rather than by an in-process rollback. COMMITTING is what the current release leaves behind;
     * PREPARED is what a journal written before this fix leaves behind.
     */
    @Test
    void processTerminationAfterManifestReplaceRecoversOnNextMutation() throws IOException {
        for (String state : new String[] {"COMMITTING", "PREPARED"}) {
            Path project = tempDir.resolve("terminated-" + state.toLowerCase(Locale.ROOT));
            Files.createDirectories(project);
            String originalManifest = project("terminated");
            String stagedManifest = originalManifest + "[versions]\ncrashed = \"9.9.9\"\n";
            Files.writeString(project.resolve("zolt.toml"), stagedManifest);
            Files.writeString(project.resolve("zolt.lock"), "lock = \"original\"\n");
            Path journal = journal(project);
            Files.createDirectories(journal);
            Files.writeString(journal.resolve("zolt.toml.backup"), originalManifest);
            Files.writeString(journal.resolve("zolt.toml.staged"), stagedManifest);
            Files.writeString(journal.resolve("zolt.lock.backup"), "lock = \"original\"\n");
            Files.writeString(journal.resolve("zolt.lock.staged"), "lock = \"staged\"\n");
            Files.writeString(journal.resolve("state"), state + "\n");

            ManifestEditResult edit = ManifestEditTransaction.execute(
                    project,
                    project.resolve("cache"),
                    true,
                    MANIFESTS,
                    null,
                    config -> AuthoredManifestMutator.setVersionAlias(
                            config, new LocalId("fresh"), new VersionAliasValue("2.0.0")));

            String committed = Files.readString(project.resolve("zolt.toml"));
            assertTrue(edit.manifestChanged(), state);
            assertFalse(
                    committed.contains("crashed"),
                    state + " journal left the abandoned edit live: " + committed);
            assertTrue(committed.contains("fresh = \"2.0.0\""), state + ": " + committed);
            assertEquals("lock = \"original\"\n", Files.readString(project.resolve("zolt.lock")), state);
            assertFalse(Files.exists(journal), state);
        }
    }

    /** The one live state Zolt refuses to interpret must say which journal and which digests. */
    @Test
    void unclassifiableLiveManifestNamesTheJournalAndBothDigests() throws IOException {
        Path root = tempDir.resolve("unclassifiable");
        Path journal = journal(root);
        Files.createDirectories(journal);
        Files.writeString(root.resolve("zolt.toml"), "manifest = \"hand edited\"\n");
        Files.writeString(root.resolve("zolt.lock"), "lock = \"original\"\n");
        writeJournalContents(journal);
        Files.writeString(journal.resolve("state"), "COMMITTING\n");

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> ManifestEditTransaction.recover(journal, root));

        String message = failure.getMessage();
        assertTrue(message.contains(journal.toString()), message);
        assertTrue(message.contains(sha256("manifest = \"original\"\n")), message);
        assertTrue(message.contains(sha256("manifest = \"staged\"\n")), message);
        assertTrue(Files.exists(journal), "an unclassifiable journal is preserved, not deleted");
        assertEquals("manifest = \"hand edited\"\n", Files.readString(root.resolve("zolt.toml")));
    }

    private static void writeJournalContents(Path journal) throws IOException {
        Files.writeString(journal.resolve("zolt.toml.backup"), "manifest = \"original\"\n");
        Files.writeString(journal.resolve("zolt.toml.staged"), "manifest = \"staged\"\n");
        Files.writeString(journal.resolve("zolt.lock.backup"), "lock = \"original\"\n");
        Files.writeString(journal.resolve("zolt.lock.staged"), "lock = \"staged\"\n");
    }

    private void seedResolvedLockfile(Path project, Path cache) {
        ManifestEditTransaction.execute(
                project,
                cache,
                false,
                MANIFESTS,
                new ResolveService(),
                config -> AuthoredManifestMutator.setVersionAlias(
                        config, new LocalId("seed"), new VersionAliasValue("0.1.0")));
    }

    private static Path journal(Path root) {
        return root.resolve(".zolt").resolve("manifest-edits").resolve("project");
    }

    private static String project(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = 21

                """.formatted(name);
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String sha256(String content) {
        try {
            return "sha256:"
                    + HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
