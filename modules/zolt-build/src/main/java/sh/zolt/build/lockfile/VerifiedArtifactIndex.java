package sh.zolt.build.lockfile;

import sh.zolt.lockfile.toml.LockfileReadException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Command-scoped memo of cached-artifact SHA-256 verifications.
 *
 * <p>A workspace command projects one lockfile into compile, runtime, test, processor, and package
 * lock views for every member, and every projection asks for the integrity of the same shared
 * artifacts. Without a shared index each projection re-hashes each jar and pom it references, so a
 * dependency common to a 200-member workspace is read from disk hundreds of times per command.
 *
 * <p>The index is deliberately lazy: nothing is hashed until a projection actually asks for a path,
 * so commands that compute no classpath pay nothing at all. Once asked, a path is hashed exactly
 * once for the lifetime of the index and every later request — from any projection, any member, any
 * thread — reuses the recorded digest. The index is scoped to a single command so artifacts modified
 * between commands are always read again.
 *
 * <p>The index never decides whether a lockfile is acceptable; it reports what a path hashes to and
 * leaves the fail-closed policy, and the diagnostics that go with it, to
 * {@link ArtifactIntegrityVerifier}. The one judgement it does make is rejecting a path that two
 * callers claim should hash to different values, because no single file can satisfy both and
 * silently honouring the first claim would let the second go unchecked.
 */
public final class VerifiedArtifactIndex {
    private final ConcurrentMap<Path, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger hashes = new AtomicInteger();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();

    /**
     * Verifies {@code artifact} against {@code expectedSha256}, hashing it only if this index has
     * not already hashed it.
     */
    public VerificationResult verifyOnce(Path artifact, String expectedSha256) {
        return verifyOnce(artifact, expectedSha256, VerifiedArtifactIndex::hashContent);
    }

    /**
     * Verifies {@code artifact} using {@code hasher} for the single hash this index performs for the
     * path. Later requests for the same path reuse that result and never call {@code hasher} again.
     */
    public VerificationResult verifyOnce(
            Path artifact,
            String expectedSha256,
            ArtifactContentHasher hasher) {
        Path path = artifact.toAbsolutePath().normalize();
        Objects.requireNonNull(expectedSha256, "expectedSha256");
        Entry created = new Entry(expectedSha256, new CompletableFuture<>());
        Entry existing = entries.putIfAbsent(path, created);
        if (existing != null) {
            requireSameExpectation(path, existing.expectedSha256(), expectedSha256);
            cacheHits.incrementAndGet();
            return result(path, existing, true);
        }
        hashInto(path, created, hasher);
        return result(path, created, false);
    }

    /**
     * Returns whether this index already holds (or is currently computing) a result for the path.
     * Callers use it to decide what still needs hashing; a concurrent request may make it stale, and
     * {@link #verifyOnce} remains the authority on hashing each path exactly once.
     */
    public boolean requested(Path artifact) {
        return entries.containsKey(artifact.toAbsolutePath().normalize());
    }

    public Metrics metrics() {
        return new Metrics(
                entries.size(),
                bytes.get(),
                hashes.get(),
                cacheHits.get(),
                nanos.get());
    }

    /**
     * Completes the entry on every path out, including one nobody planned for.
     *
     * <p>Everything between the hash and the completion — the counters, the {@code Files.size} call —
     * can throw, and any throw that escaped before {@code complete} would leave every other thread
     * that ever asks for this path blocked on {@link CompletableFuture#join()} for the life of the
     * command. The bookkeeping therefore runs inside the guarded region and the completion happens in
     * a {@code finally}, so an unexpected failure surfaces as a diagnostic for the path rather than a
     * hang.
     */
    private void hashInto(Path path, Entry entry, ArtifactContentHasher hasher) {
        long started = System.nanoTime();
        Outcome outcome = new Outcome(null, unreadable(path, null));
        try {
            try {
                outcome = new Outcome(hasher.hash(path), null);
            } catch (RuntimeException exception) {
                outcome = new Outcome(null, exception);
            }
            hashes.incrementAndGet();
            nanos.addAndGet(Math.max(0L, System.nanoTime() - started));
            if (outcome.failure() == null) {
                bytes.addAndGet(sizeOf(path));
            }
        } catch (RuntimeException | Error failure) {
            outcome = new Outcome(null, unreadable(path, failure));
            throw failure;
        } finally {
            entry.outcome().complete(outcome);
        }
    }

    private static VerificationResult result(Path path, Entry entry, boolean cacheHit) {
        Outcome outcome = entry.outcome().join();
        return new VerificationResult(
                path,
                entry.expectedSha256(),
                outcome.sha256(),
                outcome.failure(),
                cacheHit);
    }

    private static void requireSameExpectation(Path path, String recorded, String requested) {
        if (recorded.equals(requested)) {
            return;
        }
        throw new LockfileReadException(
                "Conflicting integrity expectations for cached artifact at "
                        + path
                        + ". The same file is expected to hash to both "
                        + recorded
                        + " and "
                        + requested
                        + ". Run `zolt resolve` to rewrite zolt.lock, or remove the conflicting"
                        + " entry so one checksum is recorded for the file.");
    }

    /** Best-effort, for a counter: a size we cannot read is worth zero, never a failed command. */
    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException | RuntimeException exception) {
            return 0L;
        }
    }

    private static String hashContent(Path artifact) {
        if (!Files.isRegularFile(artifact)) {
            throw new LockfileReadException(
                    "Cached artifact is missing at "
                            + artifact
                            + ". Run `zolt resolve` to download it again.");
        }
        try {
            return ArtifactContentDigest.sha256(artifact);
        } catch (IOException exception) {
            throw unreadable(artifact, exception);
        }
    }

    private static LockfileReadException unreadable(Path artifact, Throwable cause) {
        return new LockfileReadException(
                "Could not verify cached artifact at "
                        + artifact
                        + ". Check that the cache entry is readable, or remove it and run"
                        + " `zolt resolve`.",
                cause);
    }

    /** Computes the SHA-256 of an artifact, throwing a diagnostic exception when it cannot. */
    @FunctionalInterface
    public interface ArtifactContentHasher {
        String hash(Path artifact);
    }

    /**
     * The outcome of one {@link #verifyOnce} request. A result carries what the file hashed to
     * rather than a verdict, so callers that know the lock coordinate can phrase the failure.
     */
    public record VerificationResult(
            Path path,
            String expectedSha256,
            String actualSha256,
            RuntimeException failure,
            boolean cacheHit) {
        /** Whether the artifact was read and matched the expected checksum. */
        public boolean verified() {
            return failure == null && expectedSha256.equals(actualSha256);
        }
    }

    public record Metrics(
            int paths,
            long bytes,
            int hashes,
            int cacheHits,
            long nanos) {
        public static Metrics empty() {
            return new Metrics(0, 0L, 0, 0, 0L);
        }
    }

    private record Entry(
            String expectedSha256,
            CompletableFuture<Outcome> outcome) {
    }

    private record Outcome(
            String sha256,
            RuntimeException failure) {
    }
}
