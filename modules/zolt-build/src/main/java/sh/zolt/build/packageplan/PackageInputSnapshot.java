package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything packaging needs to know about one compiled output directory, read exactly once.
 *
 * <p>A single pass over each file feeds the canonical content fingerprint (which package planning,
 * reuse and evidence compare), the entry ordering, and — for files inside
 * {@link PackageInputBudget} — the bytes the archive writer emits. Files above the budget keep
 * only their identity and are streamed again at write time.
 */
public final class PackageInputSnapshot {
    static final String MISSING = "missing";
    private static final int STREAM_BUFFER_BYTES = 64 * 1024;

    private final Path root;
    private final List<PackageInputEntry> entries;
    private final String fingerprint;
    private final PackageInputReader reader;
    private final PackageInputBudget budget;
    private volatile Map<String, byte[]> retained;
    private volatile long retainedBytes;

    private PackageInputSnapshot(
            Path root,
            List<PackageInputEntry> entries,
            String fingerprint,
            PackageInputReader reader,
            PackageInputBudget budget,
            Map<String, byte[]> retained,
            long retainedBytes) {
        this.root = root;
        this.entries = List.copyOf(entries);
        this.fingerprint = fingerprint;
        this.reader = reader;
        this.budget = budget;
        this.retained = retained;
        this.retainedBytes = retainedBytes;
    }

    public static PackageInputSnapshot of(Path directory, PackageInputBudget budget) {
        return of(directory, budget, PackageInputReader.files());
    }

    public static PackageInputSnapshot of(
            Path directory,
            PackageInputBudget budget,
            PackageInputReader reader) {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return new PackageInputSnapshot(
                    root, List.of(), MISSING, reader, budget, new HashMap<>(), 0L);
        }
        try {
            return read(root, budget, reader);
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not fingerprint package application output at "
                            + root
                            + ". Check that it is readable and retry.",
                    exception);
        }
    }

    public Path root() {
        return root;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public List<PackageInputEntry> entries() {
        return entries;
    }

    public long retainedBytes() {
        return retainedBytes;
    }

    /**
     * Writes one entry's bytes without materialising oversized files.
     */
    public void transferTo(PackageInputEntry entry, OutputStream output) throws IOException {
        byte[] memory = retainedFor(entry);
        if (memory != null) {
            output.write(memory);
            return;
        }
        byte[] buffer = new byte[STREAM_BUFFER_BYTES];
        try (InputStream input = reader.open(entry.path())) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    public byte[] content(PackageInputEntry entry) throws IOException {
        byte[] memory = retainedFor(entry);
        if (memory != null) {
            return memory;
        }
        ByteArrayOutputStream collected =
                new ByteArrayOutputStream(Math.max(32, (int) Math.min(entry.size(), Integer.MAX_VALUE)));
        transferTo(entry, collected);
        return collected.toByteArray();
    }

    /**
     * Drops retained bytes once the owning archive is written and refunds the command budget.
     */
    public void release() {
        long released;
        synchronized (this) {
            if (retained.isEmpty()) {
                return;
            }
            released = retainedBytes;
            retained = Map.of();
            retainedBytes = 0L;
        }
        budget.refund(released);
    }

    private byte[] retainedFor(PackageInputEntry entry) {
        return retained.get(entry.name());
    }

    private static PackageInputSnapshot read(
            Path root,
            PackageInputBudget budget,
            PackageInputReader reader) throws IOException {
        List<PackageInputFingerprinting.SizedFile> files =
                PackageInputFingerprinting.sizedApplicationFiles(root);
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", PackageInputFingerprinting.CONTENT_SCHEMA);
        List<PackageInputEntry> entries = new ArrayList<>(files.size());
        Map<String, byte[]> retained = new HashMap<>();
        long snapshotRetained = 0L;
        byte[] buffer = null;
        for (PackageInputFingerprinting.SizedFile file : files) {
            long size = file.size();
            boolean keep = size <= budget.maxFileBytes()
                    && snapshotRetained + size <= budget.maxSnapshotBytes()
                    && budget.claim(size);
            long observedSize = size;
            hash.value("path", file.name());
            if (keep) {
                byte[] content = readFully(reader, file.path());
                hash.bytes("bytes", content);
                retained.put(file.name(), content);
                snapshotRetained += content.length;
                observedSize = content.length;
            } else {
                if (buffer == null) {
                    buffer = new byte[STREAM_BUFFER_BYTES];
                }
                hash.beginBytes("bytes", size);
                streamInto(reader, file, hash, buffer);
                hash.endBytes();
            }
            entries.add(new PackageInputEntry(file.name(), file.path(), observedSize));
        }
        return new PackageInputSnapshot(
                root, entries, hash.finish(), reader, budget, retained, snapshotRetained);
    }

    private static byte[] readFully(PackageInputReader reader, Path path) throws IOException {
        try (InputStream input = reader.open(path)) {
            return input.readAllBytes();
        }
    }

    private static void streamInto(
            PackageInputReader reader,
            PackageInputFingerprinting.SizedFile file,
            PackageCanonicalHash hash,
            byte[] buffer) throws IOException {
        long observed = 0L;
        try (InputStream input = reader.open(file.path())) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                hash.bytesChunk(buffer, 0, read);
                observed += read;
            }
        }
        requireStableSize(file.path(), file.size(), observed);
    }

    private static void requireStableSize(Path file, long declared, long observed) {
        if (declared != observed) {
            throw new PackageException(
                    "Package input "
                            + file
                            + " changed while it was being read ("
                            + declared
                            + " bytes declared, "
                            + observed
                            + " read). Re-run the build and package again.");
        }
    }
}
