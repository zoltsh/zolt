package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageOutputFingerprintIndexTest {
    @TempDir
    private Path root;

    @Test
    void oneDirectoryIsReadOnceHoweverManyMembersAskForIt() throws IOException {
        Path output = write("member/target/classes/a/First.class", "first").getParent().getParent();
        write("member/target/classes/b/Second.class", "second");
        AtomicInteger opens = new AtomicInteger();
        PackageOutputFingerprintIndex index = new PackageOutputFingerprintIndex(
                PackageInputBudget.defaults(),
                counting(opens));

        String first = index.fingerprint(output);
        String second = index.fingerprint(output.toAbsolutePath());
        PackageInputSnapshot snapshot = index.snapshot(output);

        assertEquals(first, second);
        assertEquals(2, opens.get());
        assertEquals(1, index.size());
        assertSame(snapshot, index.snapshot(output));
    }

    @Test
    void metricsCountOpensAndBytesOfTheCommand() throws IOException {
        Path output = write("member/target/classes/a/First.class", "first").getParent().getParent();
        write("member/target/classes/b/Second.class", "second");
        PackageOutputFingerprintIndex index = new PackageOutputFingerprintIndex();

        index.fingerprint(output);
        index.fingerprint(output);

        PackageInputMetrics metrics = index.metrics();
        assertEquals(1, metrics.snapshots());
        assertEquals(2L, metrics.filesRead());
        assertEquals("firstsecond".length(), metrics.bytesRead());
    }

    @Test
    void releasingBytesLeavesTheFingerprintAvailable() throws IOException {
        Path output = write("member/target/classes/a/First.class", "first").getParent().getParent();
        PackageOutputFingerprintIndex index = new PackageOutputFingerprintIndex();
        String fingerprint = index.fingerprint(output);

        index.releaseBytes(output);

        assertEquals(0L, index.snapshot(output).retainedBytes());
        assertEquals(fingerprint, index.fingerprint(output));
    }

    @Test
    void releasingAnUnknownDirectoryIsAllowed() {
        new PackageOutputFingerprintIndex().releaseBytes(root.resolve("never-snapshotted"));
    }

    @Test
    void directoryProbesAreAnsweredOnceAndMatchTheFilesystem() throws IOException {
        Path present = write("member/target/classes/a/First.class", "first").getParent();
        PackageOutputFingerprintIndex index = new PackageOutputFingerprintIndex();

        assertEquals(true, index.directoryExists(present));
        assertEquals(true, index.directoryExists(present));
        assertEquals(false, index.directoryExists(root.resolve("absent")));
    }

    private Path write(String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static PackageInputReader counting(AtomicInteger opens) {
        return path -> {
            opens.incrementAndGet();
            return openStream(path);
        };
    }

    private static InputStream openStream(Path path) throws IOException {
        return Files.newInputStream(path);
    }
}
