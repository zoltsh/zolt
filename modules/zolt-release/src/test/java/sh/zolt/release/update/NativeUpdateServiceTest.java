package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.ReleaseTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class NativeUpdateServiceTest extends NativeUpdateServiceTestCase {
    @Test
    void updatesFromLocalDevelopmentManifestWithFileArtifactUrls() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");

        NativeUpdateResult result = service.update(request(installed, channel, tempDir.resolve("update-work")));

        assertTrue(result.updated());
        assertEquals("0.1.0", result.previousVersion());
        assertEquals("0.1.1", result.availableVersion());
        assertEquals("../versions/0.1.1/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertTrue(Files.isExecutable(installed.installRoot().resolve("versions/0.1.1/bin/zolt")));
        assertEquals("0.1.0", Files.readString(installed.installRoot().resolve("previous-version")).strip());
    }

    @Test
    void rejectedCandidatePreservesPreviouslyInstalledTargetAndRollbackState() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path existingBinary = installed.installRoot().resolve("versions/0.1.1/bin/zolt");
        writeFakeZolt(existingBinary, "0.1.1");
        String original = Files.readString(existingBinary);
        Path previousVersion = installed.installRoot().resolve("previous-version");
        Files.writeString(previousVersion, "0.1.1\n");
        Path archive = archive("0.1.1", "linux-x64", "wrong-version");
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");

        assertThrows(NativeUpdateException.class,
                () -> service.update(request(installed, channel, tempDir.resolve("update-work"))));

        assertEquals(original, Files.readString(existingBinary));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertEquals("0.1.1\n", Files.readString(previousVersion));
    }

    @Test
    void createsTemporaryWorkDirectoryWhenRequestDoesNotProvideOne() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");

        NativeUpdateResult result = service.update(new NativeUpdateRequest(
                installed.installRoot(),
                installed.binLink(),
                channel.toUri(),
                ReleaseTarget.LINUX_X64,
                null));

        assertTrue(result.updated());
        assertEquals("../versions/0.1.1/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertTrue(Files.isExecutable(installed.installRoot().resolve("versions/0.1.1/bin/zolt")));
    }

    @Test
    void skipsDownloadAndWorkDirectoryWhenAlreadyCurrent() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path missingArchive = tempDir.resolve("missing/zolt-0.1.0-linux-x64.tar.gz");
        Path workDirectory = tempDir.resolve("update-work-current");
        Path channel = writeChannel(
                "stable",
                "0.1.0",
                "linux-x64",
                missingArchive,
                missingArchive.getFileName().toString(),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        NativeUpdateResult result = service.update(request(installed, channel, workDirectory));

        assertFalse(result.updated());
        assertEquals("0.1.0", result.previousVersion());
        assertEquals("0.1.0", result.availableVersion());
        assertFalse(Files.exists(workDirectory));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertFalse(Files.exists(installed.installRoot().resolve("previous-version")));
    }

    @Test
    void existingTemporaryInstallSymlinkDoesNotDeleteOutsideTarget() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");
        Path outside = tempDir.resolve("outside-temp-target");
        Path marker = outside.resolve("marker.txt");
        Files.createDirectories(outside);
        Files.writeString(marker, "keep");
        Files.createSymbolicLink(installed.installRoot().resolve("versions/0.1.1.tmp"), outside);

        NativeUpdateResult result = service.update(request(installed, channel, tempDir.resolve("update-work")));

        assertTrue(result.updated());
        assertEquals("keep", Files.readString(marker));
        assertEquals("../versions/0.1.1/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void updatesWhenInstallRootIsReachedThroughSymlinkedAncestor() throws IOException {
        // Reproduces the macOS /var -> /private/var case on any OS: the install tree is reached through a
        // symlinked ancestor, so the realpath-resolved binRoot differs from the raw bin link prefix.
        Path realHome = tempDir.resolve("real-home");
        Files.createDirectories(realHome);
        Path linkedHome = tempDir.resolve("linked-home");
        Files.createSymbolicLink(linkedHome, realHome);

        Path installRoot = linkedHome.resolve(".zolt");
        writeFakeZolt(installRoot.resolve("versions").resolve("0.1.0").resolve("bin/zolt"), "0.1.0");
        Path bin = installRoot.resolve("bin");
        Files.createDirectories(bin);
        Path binLink = bin.resolve("zolt");
        Files.createSymbolicLink(binLink, Path.of("../versions", "0.1.0", "bin", "zolt"));
        InstalledFixture installed = new InstalledFixture(installRoot, binLink);

        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");

        NativeUpdateResult result = service.update(request(installed, channel, tempDir.resolve("update-work-symlink")));

        assertTrue(result.updated());
        assertEquals("0.1.1", result.availableVersion());
        assertEquals("../versions/0.1.1/bin/zolt", Files.readSymbolicLink(binLink).toString());
    }
}
