package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageInputSnapshotTest {
    @TempDir
    private Path outputDirectory;

    @Test
    void snapshotOpensEveryInputExactlyOnceAndTheArchiveReusesThoseBytes() throws IOException {
        write("b/Second.class", "second");
        write("a/First.class", "first");
        write("a/resource.properties", "key=value");
        CountingReader reader = new CountingReader();

        PackageInputSnapshot snapshot = PackageInputSnapshot.of(
                outputDirectory,
                PackageInputBudget.defaults(),
                reader);
        for (PackageInputEntry entry : snapshot.entries()) {
            snapshot.transferTo(entry, new ByteArrayOutputStream());
        }

        assertEquals(
                Map.of(
                        "a/First.class", 1,
                        "a/resource.properties", 1,
                        "b/Second.class", 1),
                reader.opensByEntryName(outputDirectory));
    }

    @Test
    void entriesAreOrderedByArchiveEntryName() throws IOException {
        write("b/Second.class", "second");
        write("a/First.class", "first");
        write("a/Aardvark.class", "aardvark");

        PackageInputSnapshot snapshot = PackageInputSnapshot.of(
                outputDirectory,
                PackageInputBudget.defaults());

        assertEquals(
                List.of("a/Aardvark.class", "a/First.class", "b/Second.class"),
                snapshot.entries().stream().map(PackageInputEntry::name).toList());
    }

    @Test
    void fingerprintMatchesTheDirectoryFingerprintItReplaces() throws IOException {
        write("a/First.class", "first");
        write("b/Second.class", "second");

        String snapshotted = PackageInputSnapshot
                .of(outputDirectory, PackageInputBudget.defaults())
                .fingerprint();

        assertEquals(
                PackageInputFingerprinting.applicationOutputFingerprint(outputDirectory),
                snapshotted);
    }

    @Test
    void streamedInputsProduceTheSameFingerprintAsRetainedInputs() throws IOException {
        write("a/First.class", "first".repeat(4096));
        write("b/Second.class", "second");

        String retained = PackageInputSnapshot
                .of(outputDirectory, PackageInputBudget.defaults())
                .fingerprint();
        String streamed = PackageInputSnapshot
                .of(outputDirectory, PackageInputBudget.streaming())
                .fingerprint();

        assertEquals(retained, streamed);
    }

    @Test
    void contentChangesChangeTheFingerprint() throws IOException {
        write("a/First.class", "first");
        String before = PackageInputSnapshot
                .of(outputDirectory, PackageInputBudget.defaults())
                .fingerprint();

        write("a/First.class", "changed");

        assertNotEquals(
                before,
                PackageInputSnapshot
                        .of(outputDirectory, PackageInputBudget.defaults())
                        .fingerprint());
    }

    @Test
    void filesAboveTheBudgetStreamInsteadOfBeingRetained() throws IOException {
        write("small.class", "small");
        write("large.class", "large".repeat(1000));
        CountingReader reader = new CountingReader();

        PackageInputSnapshot snapshot = PackageInputSnapshot.of(
                outputDirectory,
                PackageInputBudget.of(64, 1024, 1024),
                reader);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        PackageInputEntry large = entry(snapshot, "large.class");
        snapshot.transferTo(large, written);

        assertEquals(5L, snapshot.retainedBytes());
        assertEquals(2, reader.opensFor(outputDirectory.resolve("large.class")));
        assertEquals(1, reader.opensFor(outputDirectory.resolve("small.class")));
        assertArrayEquals(Files.readAllBytes(outputDirectory.resolve("large.class")), written.toByteArray());
    }

    @Test
    void aSingleOversizedFileCannotExhaustTheCommandBudget() throws IOException {
        write("huge.class", "huge".repeat(10_000));
        write("tiny.class", "tiny");
        PackageInputBudget budget = PackageInputBudget.of(1024, 1024, 1024);

        PackageInputSnapshot snapshot = PackageInputSnapshot.of(outputDirectory, budget);

        assertEquals(4L, snapshot.retainedBytes());
        snapshot.release();
        assertEquals(0L, snapshot.retainedBytes());
        assertTrue(budget.claim(1024), "release must refund the retained bytes");
    }

    @Test
    void releasedSnapshotsStillServeTheirEntriesFromDisk() throws IOException {
        write("a/First.class", "first");
        PackageInputSnapshot snapshot = PackageInputSnapshot.of(
                outputDirectory,
                PackageInputBudget.defaults());

        snapshot.release();
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        snapshot.transferTo(entry(snapshot, "a/First.class"), written);

        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), written.toByteArray());
    }

    @Test
    void missingOutputDirectoriesReportMissingWithoutEntries() {
        PackageInputSnapshot snapshot = PackageInputSnapshot.of(
                outputDirectory.resolve("absent"),
                PackageInputBudget.defaults());

        assertEquals("missing", snapshot.fingerprint());
        assertEquals(List.of(), snapshot.entries());
    }

    @Test
    void localBuildStateIsNotPackagedOrFingerprinted() throws IOException {
        write("a/First.class", "first");
        write(".zolt-build-main.fingerprint", "state");

        PackageInputSnapshot snapshot = PackageInputSnapshot.of(
                outputDirectory,
                PackageInputBudget.defaults());

        assertEquals(
                List.of("a/First.class"),
                snapshot.entries().stream().map(PackageInputEntry::name).toList());
    }

    private static PackageInputEntry entry(PackageInputSnapshot snapshot, String name) {
        return snapshot.entries().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = outputDirectory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static final class CountingReader implements PackageInputReader {
        private final Map<Path, AtomicInteger> opens = new ConcurrentHashMap<>();

        @Override
        public InputStream open(Path path) throws IOException {
            opens.computeIfAbsent(path.toAbsolutePath().normalize(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            return Files.newInputStream(path);
        }

        int opensFor(Path path) {
            AtomicInteger count = opens.get(path.toAbsolutePath().normalize());
            return count == null ? 0 : count.get();
        }

        Map<String, Integer> opensByEntryName(Path root) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            return opens.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                    counted -> normalizedRoot.relativize(counted.getKey()).toString().replace('\\', '/'),
                    counted -> counted.getValue().get()));
        }
    }
}
