package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.release.ReleaseTarget;
import sh.zolt.release.channel.ReleaseChannelManifestException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class NativeUpdateFailureTest extends NativeUpdateServiceTestCase {
    @Test
    void checksumMismatchFailsBeforeInstallActivation() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path channel = writeChannel(
                "stable",
                "0.1.1",
                "linux-x64",
                archive,
                archive.getFileName().toString(),
                "0000000000000000000000000000000000000000000000000000000000000000");

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> service.update(request(installed, channel, tempDir.resolve("update-work-bad-checksum"))));

        assertTrue(exception.getMessage().contains("Checksum mismatch for native Zolt archive"), exception.getMessage());
        assertFalse(Files.exists(installed.installRoot().resolve("versions/0.1.1")));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void smokeVersionMismatchFailsBeforeCurrentSymlinkActivation() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.2.0");
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> service.update(request(installed, channel, tempDir.resolve("update-work-smoke-mismatch"))));

        assertTrue(exception.getMessage().contains("Downloaded native Zolt failed smoke verification"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Expected version 0.1.1"), exception.getMessage());
        assertTrue(exception.getMessage().contains("`0.2.0`"), exception.getMessage());
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void archiveWithMultipleTopLevelDirectoriesFailsBeforeInstallActivation() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = tarArchive(
                "zolt-0.1.1-linux-x64-multiple-roots.tar.gz",
                TarEntry.file("first/bin/zolt", "one"),
                TarEntry.file("second/bin/zolt", "two"));
        Path channel = writeChannel(
                "stable",
                "0.1.1",
                "linux-x64",
                archive,
                archive.getFileName().toString(),
                sha256(archive));

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> service.update(request(installed, channel, tempDir.resolve("update-work-multiple-roots"))));

        assertTrue(exception.getMessage().contains("exactly one top-level directory"), exception.getMessage());
        assertFalse(Files.exists(installed.installRoot().resolve("versions/0.1.1")));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void archiveWithoutExecutableBinaryFailsBeforeInstallActivation() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = tarArchive(
                "zolt-0.1.1-linux-x64-no-binary.tar.gz",
                TarEntry.file("zolt-0.1.1-linux-x64/README.txt", "not a binary"));
        Path channel = writeChannel(
                "stable",
                "0.1.1",
                "linux-x64",
                archive,
                archive.getFileName().toString(),
                sha256(archive));

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> service.update(request(installed, channel, tempDir.resolve("update-work-no-binary"))));

        assertTrue(exception.getMessage().contains("does not contain executable bin/zolt"), exception.getMessage());
        assertFalse(Files.exists(installed.installRoot().resolve("versions/0.1.1")));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void rejectsUnsafeRemoteChannelUrisBeforeManifestDownload() throws IOException {
        InstalledFixture installed = install("0.1.0");
        String[] unsafeChannels = {
            "http://dist.example/channel.json",
            "https://user:pass@dist.example/channel.json",
            "ftp://dist.example/channel.json",
            "jar:https://dist.example/channel.json!/channel.json"
        };

        for (String unsafeChannel : unsafeChannels) {
            NativeUpdateException exception = assertThrows(
                    NativeUpdateException.class,
                    () -> service.update(new NativeUpdateRequest(
                            installed.installRoot(),
                            installed.binLink(),
                            URI.create(unsafeChannel),
                            ReleaseTarget.LINUX_X64,
                            tempDir.resolve("update-work"))));

            assertTrue(exception.getMessage().contains("Release channel URL"), exception.getMessage());
            assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        }
    }

    @Test
    void wrapsAcceptedRemoteChannelDownloadFailureWithoutChangingCurrentInstall() throws IOException {
        InstalledFixture installed = install("0.1.0");

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> service.update(new NativeUpdateRequest(
                        installed.installRoot(),
                        installed.binLink(),
                        URI.create("https://127.0.0.1:1/channels/stable.json"),
                        ReleaseTarget.LINUX_X64,
                        tempDir.resolve("update-work-remote-failure"))));

        assertTrue(exception.getMessage().contains("Could not update native Zolt:"), exception.getMessage());
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void unsafeArchiveNameCannotEscapeUpdateWorkDirectory() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path workDirectory = tempDir.resolve("update-work");
        Path escapedArchive = tempDir.resolve("escaped.tar.gz");
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, "../escaped.tar.gz", "sidecar");

        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> service.update(request(installed, channel, workDirectory)));

        assertTrue(exception.getMessage().contains("archive"), exception.getMessage());
        assertFalse(Files.exists(escapedArchive));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void callerWorkDirectoryIsNeverModifiedEvenWhenItIsASymlinkOrFile() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");
        Path realWork = Files.createDirectory(tempDir.resolve("real-update-work"));
        Path marker = Files.writeString(realWork.resolve("marker"), "keep");
        Path linkedWork = Files.createSymbolicLink(tempDir.resolve("linked-update-work"), realWork);
        assertTrue(service.update(request(installed, channel, linkedWork)).updated());
        assertEquals("keep", Files.readString(marker));
        assertTrue(Files.isSymbolicLink(linkedWork));
        Path workFile = Files.writeString(tempDir.resolve("work-file"), "keep file");
        assertFalse(service.update(request(installed, channel, workFile)).updated());
        assertEquals("keep file", Files.readString(workFile));
    }

    @Test
    void unsafeVersionCannotEscapeInstalledVersionsDirectory() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path marker = installed.installRoot().resolve("escape/marker.txt");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "keep");
        Path channel = writeChannel("stable", "../escape", "linux-x64", archive, archive.getFileName().toString(), "sidecar");

        ReleaseChannelManifestException exception = assertThrows(
                ReleaseChannelManifestException.class,
                () -> service.update(request(installed, channel, tempDir.resolve("update-work"))));

        assertTrue(exception.getMessage().contains("version"), exception.getMessage());
        assertEquals("keep", Files.readString(marker));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void tarTraversalEntryFailsBeforeInstallActivation() throws IOException {
        InstalledFixture installed = install("0.1.0");
        String[] unsafeEntries = {
            "../zolt",
            "/tmp/zolt",
            "zolt-0.1.1-linux-x64/../../evil"
        };
        for (int index = 0; index < unsafeEntries.length; index++) {
            Path archive = tarArchive(
                    "zolt-0.1.1-linux-x64-" + index + ".tar.gz",
                    TarEntry.file(unsafeEntries[index], "oops"));
            Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");
            Path workDirectory = tempDir.resolve("update-work-" + index);

            NativeUpdateException exception = assertThrows(
                    NativeUpdateException.class,
                    () -> service.update(request(installed, channel, workDirectory)));

            assertTrue(exception.getMessage().contains("unsafe entry path"), exception.getMessage());
            assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        }
    }

    @Test
    void tarSymlinkEntryFailsBeforeInstallActivation() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = tarArchive("zolt-0.1.1-linux-x64.tar.gz", TarEntry.link("zolt-0.1.1-linux-x64/bin/zolt", '2'));
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> service.update(request(installed, channel, tempDir.resolve("update-work"))));

        assertTrue(exception.getMessage().contains("symbolic link entry"), exception.getMessage());
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void tarHardLinkEntryFailsBeforeInstallActivation() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = tarArchive("zolt-0.1.1-linux-x64.tar.gz", TarEntry.link("zolt-0.1.1-linux-x64/bin/zolt", '1'));
        Path channel = writeChannel("stable", "0.1.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> service.update(request(installed, channel, tempDir.resolve("update-work"))));

        assertTrue(exception.getMessage().contains("hard link entry"), exception.getMessage());
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }
}
