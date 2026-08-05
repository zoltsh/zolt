package sh.zolt.build.packageevidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageArchiveDigestsTest {
    @TempDir
    private Path directory;

    @Test
    void aRecordedDigestIsReturnedWithoutTouchingTheArtifact() {
        PackageArchiveDigests digests = new PackageArchiveDigests();
        Path neverWritten = directory.resolve("absent.jar");

        digests.record(neverWritten, "sha256:recorded");

        assertEquals("sha256:recorded", digests.sha256(neverWritten));
    }

    @Test
    void anUnrecordedArtifactFallsBackToReadingItOnceAndIsThenCached() throws IOException {
        Path jar = Files.writeString(directory.resolve("demo.jar"), "content");
        PackageArchiveDigests digests = new PackageArchiveDigests();

        String first = digests.sha256(jar);
        Files.delete(jar);

        assertEquals(first, digests.sha256(jar));
        assertEquals(1, digests.size());
    }

    @Test
    void theSameArtifactNamedThreeTimesResolvesToOneEntry() throws IOException {
        Path jar = Files.writeString(directory.resolve("demo.jar"), "content");
        PackageArchiveDigests digests = new PackageArchiveDigests();

        digests.record(jar, "sha256:written-while-packaging");
        digests.sha256(jar);
        digests.sha256(jar.toAbsolutePath());
        digests.sha256(directory.resolve("./demo.jar"));

        assertEquals(1, digests.size());
        assertEquals("sha256:written-while-packaging", digests.sha256(jar));
    }
}
