package sh.zolt.build.lockfile;

import sh.zolt.build.lockfile.VerifiedArtifactIndex.VerificationResult;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Applies the lockfile's fail-closed integrity policy to the artifacts a lock view references.
 *
 * <p>Hashing itself is delegated to a {@link VerifiedArtifactIndex}. When callers share one index
 * across a command, an artifact reachable from many lock projections is read once rather than once
 * per projection; the verification decision is still made independently for every lock entry.
 */
public final class ArtifactIntegrityVerifier {
    private static final int MAX_CONCURRENCY = 8;

    private final VerifiedArtifactIndex index;
    private final int concurrency;
    private final ArtifactHasher artifactHasher;

    public ArtifactIntegrityVerifier() {
        this(new VerifiedArtifactIndex());
    }

    /** Verifies against a shared index so artifacts already hashed for the command are reused. */
    public ArtifactIntegrityVerifier(VerifiedArtifactIndex index) {
        this(
                index,
                Math.min(MAX_CONCURRENCY, Runtime.getRuntime().availableProcessors()),
                ArtifactIntegrityVerifier::hash);
    }

    ArtifactIntegrityVerifier(int concurrency, ArtifactHasher artifactHasher) {
        this(new VerifiedArtifactIndex(), concurrency, artifactHasher);
    }

    private ArtifactIntegrityVerifier(
            VerifiedArtifactIndex index,
            int concurrency,
            ArtifactHasher artifactHasher) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("Artifact integrity verification concurrency must be at least 1.");
        }
        this.index = index;
        this.concurrency = concurrency;
        this.artifactHasher = artifactHasher;
    }

    public void verify(ZoltLockfile lockfile, Path cacheRoot) {
        List<ArtifactVerification> verifications = verifications(lockfile, cacheRoot);
        Map<ArtifactKey, VerificationResult> results = resolve(verifications);
        for (ArtifactVerification verification : verifications) {
            VerificationResult result = results.get(verification.key());
            if (result.failure() != null) {
                throw result.failure();
            }
            if (!result.verified()) {
                throw mismatch(
                        verification.lockPackage(),
                        verification.kind(),
                        verification.path(),
                        verification.expectedHash(),
                        result.actualSha256());
            }
            if (!result.cacheHit()) {
                VerifiedArtifactHashes.record(result.path(), result.actualSha256());
            }
        }
    }

    private static List<ArtifactVerification> verifications(ZoltLockfile lockfile, Path cacheRoot) {
        Path normalizedCacheRoot = cacheRoot.toAbsolutePath().normalize();
        List<ArtifactVerification> verifications = new ArrayList<>();
        for (LockPackage lockPackage : lockfile.packages()) {
            addArtifact(
                    verifications,
                    lockPackage,
                    normalizedCacheRoot,
                    "jar",
                    lockPackage.jar(),
                    lockPackage.jarSha256());
            addArtifact(
                    verifications,
                    lockPackage,
                    normalizedCacheRoot,
                    "pom",
                    lockPackage.pom(),
                    lockPackage.pomSha256());
            addArtifact(
                    verifications,
                    lockPackage,
                    normalizedCacheRoot,
                    lockPackage.artifactType().orElse("artifact"),
                    lockPackage.artifact(),
                    lockPackage.artifactSha256());
        }
        return verifications;
    }

    private static void addArtifact(
            List<ArtifactVerification> verifications,
            LockPackage lockPackage,
            Path cacheRoot,
            String kind,
            Optional<String> relativePath,
            Optional<String> expectedHash) {
        if (relativePath.isEmpty() || expectedHash.isEmpty()) {
            return;
        }
        Path artifactPath = cacheRoot.resolve(relativePath.orElseThrow()).normalize();
        verifications.add(new ArtifactVerification(
                lockPackage,
                kind,
                artifactPath,
                expectedHash.orElseThrow()));
    }

    private VerificationResult verifyOnce(ArtifactVerification verification) {
        return index.verifyOnce(
                verification.path(),
                verification.expectedHash(),
                path -> artifactHasher.hash(
                        path,
                        verification.lockPackage(),
                        verification.kind(),
                        verification.expectedHash()));
    }

    /**
     * Resolves one result per distinct artifact claim this lock view makes. Claims are keyed by path
     * <em>and</em> expected checksum, so a lock view that expects two different checksums for one
     * file still reaches the index and is rejected there rather than being collapsed away here.
     *
     * <p>Artifacts the index has not read yet are read in parallel; the rest come straight from the
     * index, so a command's later lock projections do no I/O at all.
     */
    private Map<ArtifactKey, VerificationResult> resolve(List<ArtifactVerification> verifications) {
        Map<ArtifactKey, ArtifactVerification> distinct = new LinkedHashMap<>();
        for (ArtifactVerification verification : verifications) {
            distinct.putIfAbsent(verification.key(), verification);
        }
        List<ArtifactVerification> unread = distinct.values().stream()
                .filter(verification -> !index.requested(verification.path()))
                .toList();
        unread.forEach(verification -> VerifiedArtifactHashes.invalidate(verification.path()));
        Map<ArtifactKey, VerificationResult> results = new ConcurrentHashMap<>();
        if (unread.size() > 1 && concurrency > 1) {
            readInParallel(unread, results);
        }
        for (ArtifactVerification verification : distinct.values()) {
            if (!results.containsKey(verification.key())) {
                results.put(verification.key(), verifyOnce(verification));
            }
        }
        return results;
    }

    private void readInParallel(
            List<ArtifactVerification> unread,
            Map<ArtifactKey, VerificationResult> results) {
        try (ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(concurrency, unread.size()))) {
            List<Future<?>> futures = new ArrayList<>();
            for (ArtifactVerification verification : unread) {
                futures.add(executor.submit(
                        () -> results.put(verification.key(), verifyOnce(verification))));
            }
            futures.forEach(ArtifactIntegrityVerifier::await);
        }
    }

    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LockfileReadException(
                    "Artifact integrity verification was interrupted.", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new LockfileReadException(
                    "Could not verify cached artifact integrity.", exception.getCause());
        }
    }

    private static String hash(
            Path artifactPath,
            LockPackage lockPackage,
            String kind,
            String expected) {
        if (!Files.isRegularFile(artifactPath)) {
            throw mismatch(lockPackage, kind, artifactPath, expected, "missing file");
        }
        try {
            return ArtifactContentDigest.sha256(artifactPath);
        } catch (IOException exception) {
            throw new LockfileReadException(
                    "Could not verify cached "
                            + kind
                            + " for "
                            + coordinate(lockPackage)
                            + " at "
                            + artifactPath
                            + ". Check that the cache entry is readable, or remove it and run `zolt resolve`.",
                    exception);
        }
    }

    private static LockfileReadException mismatch(
            LockPackage lockPackage,
            String kind,
            Path artifactPath,
            String expected,
            String actual) {
        return new LockfileReadException(
                "Cached "
                        + kind
                        + " integrity check failed for "
                        + coordinate(lockPackage)
                        + " at "
                        + artifactPath
                        + ". Expected "
                        + expected
                        + " but found "
                        + actual
                        + ". Remove the cache entry or run `zolt resolve` to download it again.");
    }

    private static String coordinate(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + lockPackage.version();
    }

    @FunctionalInterface
    interface ArtifactHasher {
        String hash(
                Path artifactPath,
                LockPackage lockPackage,
                String kind,
                String expectedHash);
    }

    private record ArtifactVerification(
            LockPackage lockPackage,
            String kind,
            Path path,
            String expectedHash) {
        ArtifactKey key() {
            return new ArtifactKey(path, expectedHash);
        }
    }

    /** What a lock view claims about one file: where it is and what it must hash to. */
    private record ArtifactKey(
            Path path,
            String expectedHash) {
    }
}
