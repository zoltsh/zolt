package sh.zolt.build.lockfile;

import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ArtifactIntegrityVerifier {
    private static final int MAX_CONCURRENCY = 8;
    private static final int HASH_BUFFER_SIZE = 64 * 1024;

    private final Map<Path, String> verifiedHashes = new HashMap<>();
    private final int concurrency;
    private final ArtifactHasher artifactHasher;

    public ArtifactIntegrityVerifier() {
        this(
                Math.min(MAX_CONCURRENCY, Runtime.getRuntime().availableProcessors()),
                ArtifactIntegrityVerifier::hash);
    }

    ArtifactIntegrityVerifier(int concurrency, ArtifactHasher artifactHasher) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("Artifact integrity verification concurrency must be at least 1.");
        }
        this.concurrency = concurrency;
        this.artifactHasher = artifactHasher;
    }

    public void verify(ZoltLockfile lockfile, Path cacheRoot) {
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
        Set<Path> previouslyVerified = Set.copyOf(verifiedHashes.keySet());
        Map<Path, RuntimeException> hashFailures = hashUnverifiedArtifacts(verifications);
        for (ArtifactVerification verification : verifications) {
            RuntimeException hashFailure = hashFailures.get(verification.path());
            if (hashFailure != null) {
                throw hashFailure;
            }
            String actual = verifiedHashes.get(verification.path());
            if (!verification.expectedHash().equals(actual)) {
                throw mismatch(
                        verification.lockPackage(),
                        verification.kind(),
                        verification.path(),
                        verification.expectedHash(),
                        actual);
            }
            if (!previouslyVerified.contains(verification.path())) {
                VerifiedArtifactHashes.record(verification.path(), actual);
            }
        }
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

    private Map<Path, RuntimeException> hashUnverifiedArtifacts(
            List<ArtifactVerification> verifications) {
        Map<Path, ArtifactVerification> unverified = new LinkedHashMap<>();
        for (ArtifactVerification verification : verifications) {
            if (!verifiedHashes.containsKey(verification.path())) {
                unverified.putIfAbsent(verification.path(), verification);
            }
        }
        if (unverified.isEmpty()) {
            return Map.of();
        }
        unverified.keySet().forEach(VerifiedArtifactHashes::invalidate);
        Map<Path, RuntimeException> failures = new HashMap<>();
        if (unverified.size() == 1 || concurrency == 1) {
            for (ArtifactVerification verification : unverified.values()) {
                ArtifactHashResult result = hashResult(verification);
                recordResult(verification.path(), result, failures);
            }
            return failures;
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(concurrency, unverified.size()))) {
            Map<Path, Future<ArtifactHashResult>> futures = new LinkedHashMap<>();
            for (ArtifactVerification verification : unverified.values()) {
                futures.put(
                        verification.path(),
                        executor.submit(() -> hashResult(verification)));
            }
            for (Map.Entry<Path, Future<ArtifactHashResult>> entry : futures.entrySet()) {
                recordResult(entry.getKey(), awaitHash(entry.getValue()), failures);
            }
        }
        return failures;
    }

    private String hash(ArtifactVerification verification) {
        return artifactHasher.hash(
                verification.path(),
                verification.lockPackage(),
                verification.kind(),
                verification.expectedHash());
    }

    private ArtifactHashResult hashResult(ArtifactVerification verification) {
        try {
            return new ArtifactHashResult(hash(verification), null);
        } catch (RuntimeException exception) {
            return new ArtifactHashResult(null, exception);
        }
    }

    private void recordResult(
            Path path,
            ArtifactHashResult result,
            Map<Path, RuntimeException> failures) {
        if (result.failure() == null) {
            verifiedHashes.put(path, result.hash());
        } else {
            failures.put(path, result.failure());
        }
    }

    private static ArtifactHashResult awaitHash(Future<ArtifactHashResult> future) {
        try {
            return future.get();
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
        try (InputStream input = Files.newInputStream(artifactPath)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[HASH_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
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
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
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
    }

    private record ArtifactHashResult(
            String hash,
            RuntimeException failure) {
    }
}
