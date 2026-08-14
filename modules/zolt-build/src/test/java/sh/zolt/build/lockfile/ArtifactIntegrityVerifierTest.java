package sh.zolt.build.lockfile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

final class ArtifactIntegrityVerifierTest {
    @TempDir
    private Path tempDir;

    private final ArtifactIntegrityVerifier verifier = new ArtifactIntegrityVerifier();

    @BeforeEach
    void clearVerifiedArtifactHashes() {
        VerifiedArtifactHashes.clear();
    }

    @Test
    void acceptsCachedArtifactsThatMatchLockfileHashes() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "jar bytes");
        Path pom = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.pom"), "pom bytes");
        Path artifact = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.properties"), "properties bytes");

        String jarHash = sha256(jar).orElseThrow();
        assertDoesNotThrow(() -> verifier.verify(lockfile(
                relative(cacheRoot, jar),
                Optional.of(jarHash),
                relative(cacheRoot, pom),
                sha256(pom),
                relative(cacheRoot, artifact),
                sha256(artifact)), cacheRoot));
        assertEquals(Optional.of(jarHash), VerifiedArtifactHashes.currentHash(jar));
    }

    @Test
    void rejectsCachedJarThatDoesNotMatchLockfileHash() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "actual jar bytes");

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> verifier.verify(lockfile(
                        relative(cacheRoot, jar),
                        sha256("expected jar bytes"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()), cacheRoot));

        assertTrue(exception.getMessage().contains("Cached jar integrity check failed for com.example:demo:1.0.0"));
        assertTrue(exception.getMessage().contains("Expected " + sha256("expected jar bytes").orElseThrow()));
        assertTrue(exception.getMessage().contains("but found " + sha256(jar).orElseThrow()));
        assertTrue(exception.getMessage().contains("Remove the cache entry or run `zolt resolve`"));
        assertEquals(Optional.empty(), VerifiedArtifactHashes.currentHash(jar));
    }

    @Test
    void invalidatesPublishedHashWhenArtifactChanges() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "jar bytes");
        ZoltLockfile lockfile = lockfile(
                relative(cacheRoot, jar),
                sha256(jar),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        verifier.verify(lockfile, cacheRoot);
        Files.writeString(jar, "changed artifact bytes", StandardCharsets.UTF_8);

        assertEquals(Optional.empty(), VerifiedArtifactHashes.currentHash(jar));
    }

    @Test
    void rejectsMissingCachedArtifactWithRemediation() {
        Path cacheRoot = tempDir.resolve("cache");

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> verifier.verify(lockfile(
                        Optional.of("com/example/demo/1.0.0/demo-1.0.0.jar"),
                        sha256("expected jar bytes"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()), cacheRoot));

        assertTrue(exception.getMessage().contains("but found missing file"));
        assertTrue(exception.getMessage().contains("Remove the cache entry or run `zolt resolve`"));
    }

    @Test
    void rejectsMatchingArtifactReachedThroughEscapingSymlink() throws IOException {
        Path cacheRoot = Files.createDirectories(tempDir.resolve("cache"));
        Path outside = write(tempDir.resolve("outside/demo.jar"), "matching jar bytes");
        try {
            Files.createSymbolicLink(cacheRoot.resolve("linked"), outside.getParent());
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> verifier.verify(lockfile(
                        Optional.of("linked/demo.jar"),
                        sha256(outside),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()), cacheRoot));

        assertTrue(exception.getMessage().contains("symbolic link"));
    }

    @Test
    void reportsFailuresInLockfileArtifactOrder() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "actual jar bytes");

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> verifier.verify(lockfile(
                        relative(cacheRoot, jar),
                        sha256("expected jar bytes"),
                        Optional.of("com/example/demo/1.0.0/demo-1.0.0.pom"),
                        sha256("expected pom bytes"),
                        Optional.empty(),
                        Optional.empty()), cacheRoot));

        assertTrue(exception.getMessage().contains("Cached jar integrity check failed"));
    }

    @Test
    void cachesSuccessfulVerificationForCommandInvocation() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "original jar bytes");
        ZoltLockfile lockfile = lockfile(
                relative(cacheRoot, jar),
                sha256(jar),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        verifier.verify(lockfile, cacheRoot);
        Files.writeString(jar, "changed after verification", StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> verifier.verify(lockfile, cacheRoot));
    }

    @Test
    void hashesIndependentCachedArtifactsConcurrently() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "jar bytes");
        Path pom = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.pom"), "pom bytes");
        Path artifact = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.properties"), "properties bytes");
        CountDownLatch firstPairStarted = new CountDownLatch(2);
        AtomicInteger activeHashes = new AtomicInteger();
        AtomicInteger maximumActiveHashes = new AtomicInteger();
        ArtifactIntegrityVerifier concurrentVerifier = new ArtifactIntegrityVerifier(
                2,
                (path, lockPackage, kind, expectedHash) -> {
                    int active = activeHashes.incrementAndGet();
                    maximumActiveHashes.accumulateAndGet(active, Math::max);
                    firstPairStarted.countDown();
                    try {
                        assertTrue(firstPairStarted.await(2, TimeUnit.SECONDS));
                        return expectedHash;
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("Interrupted while waiting for concurrent hashes.", exception);
                    } finally {
                        activeHashes.decrementAndGet();
                    }
                });

        concurrentVerifier.verify(lockfile(
                relative(cacheRoot, jar),
                sha256(jar),
                relative(cacheRoot, pom),
                sha256(pom),
                relative(cacheRoot, artifact),
                sha256(artifact)), cacheRoot);

        assertEquals(2, maximumActiveHashes.get());
    }

    @Test
    void rejectsArtifactsWithoutRecordedChecksums() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "jar bytes");

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> verifier.verify(lockfile(
                        relative(cacheRoot, jar),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()), cacheRoot));

        assertTrue(exception.getMessage().contains("cache path and SHA-256 checksum must be recorded together"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
    }

    @Test
    void rejectsChecksumsWithoutRecordedArtifactPaths() {
        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> verifier.verify(lockfile(
                        Optional.empty(),
                        sha256("jar bytes"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()), tempDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("cache path and SHA-256 checksum must be recorded together"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
    }

    @Test
    void readsSharedArtifactsOnceAcrossTheLockProjectionsOfOneCommand() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "jar bytes");
        Path pom = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.pom"), "pom bytes");
        ZoltLockfile projection = lockfile(
                relative(cacheRoot, jar),
                sha256(jar),
                relative(cacheRoot, pom),
                sha256(pom),
                Optional.empty(),
                Optional.empty());
        VerifiedArtifactIndex index = new VerifiedArtifactIndex();

        // A command projects the same lockfile once per member and lane; each projection builds its
        // own verifier, and only the shared index keeps that from re-reading the same files.
        for (int projection2 = 0; projection2 < 6; projection2++) {
            new ArtifactIntegrityVerifier(index).verify(projection, cacheRoot);
        }

        assertEquals(2, index.metrics().hashes());
        assertEquals(2, index.metrics().paths());
        assertEquals(10, index.metrics().cacheHits());
        assertEquals(sha256(jar), VerifiedArtifactHashes.currentHash(jar));
    }

    @Test
    void rejectsALockViewExpectingTwoChecksumsForOneFile() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "jar bytes");
        // The same file is listed as this package's jar and as its secondary artifact, but with
        // different checksums. Only one can hold, so neither claim may be waved through.
        ZoltLockfile conflicting = lockfile(
                relative(cacheRoot, jar),
                sha256(jar),
                Optional.empty(),
                Optional.empty(),
                relative(cacheRoot, jar),
                sha256("some other bytes"));

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> new ArtifactIntegrityVerifier().verify(conflicting, cacheRoot));

        assertTrue(exception.getMessage().contains("Conflicting integrity expectations"));
        assertTrue(exception.getMessage().contains(jar.toAbsolutePath().normalize().toString()));
    }

    @Test
    void keepsCorruptionFailingClosedWhenTheIndexIsShared() throws IOException {
        Path cacheRoot = tempDir.resolve("cache");
        Path jar = write(cacheRoot.resolve("com/example/demo/1.0.0/demo-1.0.0.jar"), "actual jar bytes");
        ZoltLockfile projection = lockfile(
                relative(cacheRoot, jar),
                sha256("expected jar bytes"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        VerifiedArtifactIndex index = new VerifiedArtifactIndex();

        for (int attempt = 0; attempt < 3; attempt++) {
            LockfileReadException exception = assertThrows(
                    LockfileReadException.class,
                    () -> new ArtifactIntegrityVerifier(index).verify(projection, cacheRoot));
            assertTrue(exception.getMessage().contains(
                    "Cached jar integrity check failed for com.example:demo:1.0.0"));
        }

        assertEquals(1, index.metrics().hashes());
        assertEquals(Optional.empty(), VerifiedArtifactHashes.currentHash(jar));
    }

    /**
     * Two members' lock views hitting one command's index at the same time. Exactly one verifier
     * reads each file and publishes its fingerprint; the other must not drop that fingerprint on the
     * way past, or the rest of the command re-reads a jar it has already verified.
     *
     * <p>The two views are deliberately lopsided — one wide, one naming only the wide view's last
     * artifact — because that is the shape the fingerprint used to be lost in: the wide verifier
     * decided up front which paths looked unread, and could then drop an entry the narrow verifier
     * had published in between. Invalidation now happens inside the single hash the index grants for
     * a path, so only the verifier that actually re-reads a file can drop its fingerprint, and this
     * test guards that the two stay paired.
     */
    @Test
    void keepsTheRecordedFingerprintWhenVerifiersRaceForTheSameArtifacts() throws Exception {
        Path cacheRoot = tempDir.resolve("cache");
        List<Path> wideJars = new ArrayList<>();
        for (int number = 0; number < 40; number++) {
            wideJars.add(write(
                    cacheRoot.resolve("com/example/wide" + number + "/1.0.0/wide-1.0.0.jar"),
                    "wide jar bytes " + number));
        }
        Path shared = wideJars.getLast();
        ZoltLockfile wideView = wideLockfile(cacheRoot, wideJars);
        ZoltLockfile narrowView = wideLockfile(cacheRoot, List.of(shared));

        for (int round = 0; round < 4; round++) {
            VerifiedArtifactHashes.clear();
            VerifiedArtifactIndex index = new VerifiedArtifactIndex();
            CountDownLatch start = new CountDownLatch(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> projections = new ArrayList<>();
                for (ZoltLockfile view : List.of(wideView, narrowView)) {
                    projections.add(executor.submit(() -> {
                        start.countDown();
                        start.await();
                        new ArtifactIntegrityVerifier(index).verify(view, cacheRoot);
                        return null;
                    }));
                }
                for (Future<?> pending : projections) {
                    pending.get(30, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            assertEquals(40, index.metrics().hashes(), "round " + round);
            assertEquals(
                    sha256(shared),
                    VerifiedArtifactHashes.currentHash(shared),
                    "round " + round);
        }
    }

    private static ZoltLockfile wideLockfile(Path cacheRoot, List<Path> jars) throws IOException {
        List<LockPackage> packages = new ArrayList<>();
        for (Path jar : jars) {
            packages.add(new LockPackage(
                    new PackageId("com.example", jar.getParent().getParent().getFileName().toString()),
                    "1.0.0",
                    "maven-central",
                    DependencyScope.COMPILE,
                    true,
                    relative(cacheRoot, jar),
                    Optional.empty(),
                    sha256(jar),
                    Optional.empty(),
                    List.of()));
        }
        return new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, packages, List.of());
    }

    private static ZoltLockfile lockfile(
            Optional<String> jar,
            Optional<String> jarSha256,
            Optional<String> pom,
            Optional<String> pomSha256,
            Optional<String> artifact,
            Optional<String> artifactSha256) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(new LockPackage(
                        new PackageId("com.example", "demo"),
                        "1.0.0",
                        "maven-central",
                        DependencyScope.COMPILE,
                        true,
                        jar,
                        pom,
                        jarSha256,
                        pomSha256,
                        artifact,
                        artifact.map(ignored -> "properties"),
                        artifactSha256,
                        List.of())),
                List.of());
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }

    private static Optional<String> relative(Path root, Path path) {
        return Optional.of(root.relativize(path).toString().replace('\\', '/'));
    }

    private static Optional<String> sha256(Path path) throws IOException {
        return sha256(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static Optional<String> sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Optional.of(HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
