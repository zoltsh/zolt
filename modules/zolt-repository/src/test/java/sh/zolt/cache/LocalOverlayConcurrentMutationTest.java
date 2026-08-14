package sh.zolt.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.maven.Coordinate;

final class LocalOverlayConcurrentMutationTest {
    private static final Coordinate LIB = new Coordinate("com.example", "lib", java.util.Optional.of("1.0.0"));

    @TempDir
    private Path tempDir;

    @Test
    void concurrentPomReplacementRetriesFromOneStableSnapshot() throws Exception {
        assertConcurrentReplacement("lib-1.0.0.pom", "old pom", "replacement pom bytes");
    }

    @Test
    void concurrentArtifactReplacementRetriesFromOneStableSnapshot() throws Exception {
        assertConcurrentReplacement("lib-1.0.0.jar", "old jar", "replacement artifact bytes");
    }

    @Test
    void continuallyChangingOverlayIsRejectedWithoutPublishingAnIndex() throws Exception {
        Path root = tempDir.resolve("continual-change");
        Path source = tempDir.resolve("changing.jar");
        Files.writeString(source, "initial");
        LocalArtifactSnapshotter snapshots = new LocalArtifactSnapshotter(
                (path, attempt) -> Files.writeString(
                        path,
                        "-changed-" + attempt,
                        StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND));
        ScopedArtifactCacheStorage storage = new ScopedArtifactCacheStorage(root, snapshots);
        RepositoryCacheScope scope = RepositoryCacheScope.of("changing overlay");
        String mavenPath = mavenPath("lib-1.0.0.jar");

        ArtifactCacheException exception = assertThrows(
                ArtifactCacheException.class,
                () -> storage.storeLocal(scope, LIB, mavenPath, "local-overlay:test", source));

        assertTrue(exception.getMessage().contains("changed while Zolt was snapshotting it"));
        assertTrue(storage.find(scope, LIB, mavenPath).isEmpty());
        assertStagingIsEmpty(root);
    }

    private void assertConcurrentReplacement(String fileName, String original, String replacement) throws Exception {
        Path root = tempDir.resolve(fileName);
        Path source = tempDir.resolve("source-" + fileName);
        Files.writeString(source, original);
        CountDownLatch firstCopyFinished = new CountDownLatch(1);
        CountDownLatch replacementFinished = new CountDownLatch(1);
        LocalArtifactSnapshotter snapshots = new LocalArtifactSnapshotter((path, attempt) -> {
            if (attempt != 1) {
                return;
            }
            firstCopyFinished.countDown();
            await(replacementFinished);
        });
        ScopedArtifactCacheStorage storage = new ScopedArtifactCacheStorage(root, snapshots);
        RepositoryCacheScope scope = RepositoryCacheScope.of("concurrent " + fileName);
        String mavenPath = mavenPath(fileName);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<CachedArtifact> materialization = executor.submit(
                () -> storage.storeLocal(scope, LIB, mavenPath, "local-overlay:test", source));
        try {
            assertTrue(firstCopyFinished.await(10, TimeUnit.SECONDS));
            byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);
            Files.write(source, replacementBytes);
            replacementFinished.countDown();

            CachedArtifact artifact = materialization.get(10, TimeUnit.SECONDS);

            assertArrayEquals(replacementBytes, Files.readAllBytes(artifact.cachePath()));
            assertEquals(replacementBytes.length, artifact.size());
            assertEquals(sha256(replacementBytes), artifact.sha256());
            assertTrue(artifact.repositoryPath().contains("/" + artifact.sha256() + "/"));
            assertEquals(artifact, storage.find(scope, LIB, mavenPath).orElseThrow());
            assertStagingIsEmpty(root);
        } finally {
            replacementFinished.countDown();
            executor.shutdownNow();
        }
    }

    private static String mavenPath(String fileName) {
        return "com/example/lib/1.0.0/" + fileName;
    }

    private static void assertStagingIsEmpty(Path root) throws IOException {
        Path staging = root.resolve("staging/local-overlay");
        try (var files = Files.list(staging)) {
            assertEquals(0, files.count());
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting for concurrent source replacement");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for concurrent source replacement", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
