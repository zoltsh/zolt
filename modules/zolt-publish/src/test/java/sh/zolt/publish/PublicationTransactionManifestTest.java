package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PublicationTransactionManifestTest {
    @TempDir
    private Path tempDir;

    @Test
    void roundTripsTargetSigningIdentityAndExactStagedHashes() {
        List<StagedPublicationFile> staged = staged();
        Path state = tempDir.resolve("publish-resume.manifest");
        PublicationTransactionManifest.of(
                        "https://repo.example/releases",
                        "key=ABCD1234;sde=1700000000",
                        staged)
                .write(state);

        PublicationTransactionManifest manifest =
                PublicationTransactionManifest.read(state).orElseThrow();

        manifest.requireIdentity(
                "https://repo.example/releases",
                "key=ABCD1234;sde=1700000000");
        manifest.requirePlan(staged);
        assertEquals(
                Map.of(
                        "com/acme/demo/1.0.0/demo-1.0.0.jar",
                        "a".repeat(64),
                        "com/acme/demo/1.0.0/demo-1.0.0.jar.asc",
                        "b".repeat(64)),
                manifest.resume().recordedHashes());
    }

    @Test
    void refusesAChangedTargetOrSigningIdentity() {
        PublicationTransactionManifest manifest = PublicationTransactionManifest.of(
                "https://repo-a.example/releases",
                "key=AAAA;sde=none",
                staged());

        PublishException target = assertThrows(
                PublishException.class,
                () -> manifest.requireIdentity(
                        "https://repo-b.example/releases",
                        "key=AAAA;sde=none"));
        PublishException signing = assertThrows(
                PublishException.class,
                () -> manifest.requireIdentity(
                        "https://repo-a.example/releases",
                        "key=BBBB;sde=none"));

        assertTrue(target.getMessage().contains("repository target changed"));
        assertTrue(signing.getMessage().contains("signing identity changed"));
    }

    @Test
    void refusesAnAddedRemovedOrByteChangedStagedPath() {
        PublicationTransactionManifest manifest = PublicationTransactionManifest.of(
                "https://repo.example/releases",
                "unsigned",
                staged());
        List<StagedPublicationFile> changed = List.of(new StagedPublicationFile(
                "com/acme/demo/1.0.0/demo-1.0.0.jar",
                Path.of("staging/demo.jar"),
                "c".repeat(64)));

        PublishException exception =
                assertThrows(PublishException.class, () -> manifest.requirePlan(changed));

        assertTrue(exception.getMessage().contains("path set or exact staged bytes changed"));
    }

    @Test
    void successfulCleanupRemovesOnlyTheCompletedTransactionDirectory() throws Exception {
        Path stagingRoot = tempDir.resolve("staging");
        Path completedManifest = PublicationTransactionManifest.transactionPath(
                stagingRoot, "https://repo-a.example/releases", "com.acme:demo:1.0.0");
        Path siblingManifest = PublicationTransactionManifest.transactionPath(
                stagingRoot, "https://repo-b.example/releases", "com.acme:demo:1.0.0");
        PublicationTransactionManifest.of(
                        "https://repo-a.example/releases", "unsigned", staged())
                .write(completedManifest);
        PublicationTransactionManifest.of(
                        "https://repo-b.example/releases", "unsigned", staged())
                .write(siblingManifest);
        Path completedFile = completedManifest.getParent().resolve("files/demo.jar");
        Path siblingFile = siblingManifest.getParent().resolve("files/demo.jar");
        Files.createDirectories(completedFile.getParent());
        Files.createDirectories(siblingFile.getParent());
        Files.writeString(completedFile, "completed");
        Files.writeString(siblingFile, "sibling");

        PublicationTransactionManifest.deleteTransaction(completedManifest);

        assertFalse(Files.exists(completedManifest.getParent()));
        assertTrue(Files.isRegularFile(siblingManifest));
        assertTrue(Files.isRegularFile(siblingFile));
    }

    private static List<StagedPublicationFile> staged() {
        return List.of(
                new StagedPublicationFile(
                        "com/acme/demo/1.0.0/demo-1.0.0.jar",
                        Path.of("staging/demo.jar"),
                        "a".repeat(64)),
                new StagedPublicationFile(
                        "com/acme/demo/1.0.0/demo-1.0.0.jar.asc",
                        Path.of("staging/demo.jar.asc"),
                        "b".repeat(64)));
    }
}
