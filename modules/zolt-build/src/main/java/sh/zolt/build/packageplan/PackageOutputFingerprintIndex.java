package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.LongAdder;

/**
 * Command-scoped package inputs: one snapshot per compiled output directory, read once.
 *
 * <p>Package planning, reuse comparison and archive assembly all resolve through this index, so a
 * directory shared by several members is walked and read a single time per command.
 */
public final class PackageOutputFingerprintIndex {
    private final Map<Path, FutureTask<PackageInputSnapshot>> snapshots =
            new ConcurrentHashMap<>();
    private final Map<Path, Boolean> directories = new ConcurrentHashMap<>();
    private final LongAdder filesRead = new LongAdder();
    private final LongAdder bytesRead = new LongAdder();
    private final PackageInputBudget budget;
    private final PackageInputReader reader;

    public PackageOutputFingerprintIndex() {
        this(PackageInputBudget.defaults(), PackageInputReader.files());
    }

    public PackageOutputFingerprintIndex(PackageInputBudget budget) {
        this(budget, PackageInputReader.files());
    }

    public PackageOutputFingerprintIndex(PackageInputBudget budget, PackageInputReader reader) {
        this.budget = budget == null ? PackageInputBudget.defaults() : budget;
        this.reader = reader == null ? PackageInputReader.files() : reader;
    }

    public String fingerprint(Path outputDirectory) {
        return snapshot(outputDirectory).fingerprint();
    }

    public PackageInputSnapshot snapshot(Path outputDirectory) {
        Path normalized = outputDirectory.toAbsolutePath().normalize();
        FutureTask<PackageInputSnapshot> existing = snapshots.get(normalized);
        if (existing == null) {
            FutureTask<PackageInputSnapshot> created = new FutureTask<>(() -> {
                PackageInputSnapshot built = PackageInputSnapshot.of(normalized, budget, reader);
                filesRead.add(built.entries().size());
                bytesRead.add(built.entries().stream()
                        .mapToLong(PackageInputEntry::size)
                        .sum());
                return built;
            });
            existing = snapshots.putIfAbsent(normalized, created);
            if (existing == null) {
                existing = created;
                created.run();
            }
        }
        return await(normalized, existing);
    }

    /**
     * Frees the in-memory bytes of a directory whose archive is already written.
     */
    public void releaseBytes(Path outputDirectory) {
        FutureTask<PackageInputSnapshot> task =
                snapshots.get(outputDirectory.toAbsolutePath().normalize());
        if (task != null && task.isDone()) {
            await(outputDirectory, task).release();
        }
    }

    /**
     * Remembers which candidate directories exist while workspace inputs are located.
     *
     * <p>Every member re-walks its ancestors for every workspace dependency, and the candidates
     * near the workspace root repeat across members. Caching the probe keeps the search order and
     * the answer identical while collapsing the repeated stats.
     */
    public boolean directoryExists(Path candidate) {
        return directories.computeIfAbsent(candidate, Files::isDirectory);
    }

    public int size() {
        return snapshots.size();
    }

    /**
     * How much of the compiled output this command actually read, for the timing attribute bag.
     */
    public PackageInputMetrics metrics() {
        return new PackageInputMetrics(snapshots.size(), filesRead.sum(), bytesRead.sum());
    }

    private static PackageInputSnapshot await(
            Path directory,
            FutureTask<PackageInputSnapshot> task) {
        try {
            return task.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PackageException(
                    "Interrupted while reading package inputs under " + directory + ".",
                    exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new PackageException(
                    "Could not read package inputs under " + directory + ".",
                    cause);
        }
    }
}
