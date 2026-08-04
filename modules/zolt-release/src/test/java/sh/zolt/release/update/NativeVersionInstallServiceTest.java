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

final class NativeVersionInstallServiceTest extends NativeUpdateServiceTestCase {
    private final NativeVersionInstallService installService = new NativeVersionInstallService();

    @Test
    void installsSelectedVersionWithoutSwitchingCurrentSymlink() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path index = writeReleaseIndex("stable", "0.1.1", "linux-x64", archive, "sidecar");

        NativeVersionInstallResult result = installService.install(new NativeVersionInstallRequest(
                installed.installRoot(),
                installed.binLink(),
                index.toUri(),
                "0.1.1",
                ReleaseTarget.LINUX_X64,
                tempDir.resolve("install-work")));

        assertTrue(result.installed());
        assertEquals("stable", result.channel());
        assertEquals("0.1.1", result.version());
        assertEquals(ReleaseTarget.LINUX_X64, result.target());
        assertTrue(Files.isExecutable(installed.installRoot().resolve("versions/0.1.1/bin/zolt")));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertFalse(Files.exists(installed.installRoot().resolve("previous-version")));
    }

    @Test
    void alreadyInstalledVersionIsVerifiedAndDoesNotCreateWorkDirectory() throws IOException {
        InstalledFixture installed = install("0.1.0");
        writeFakeZolt(installed.installRoot().resolve("versions/0.1.1/bin/zolt"), "0.1.1");
        Path index = writeReleaseIndex(
                "stable",
                "0.1.1",
                "linux-x64",
                tempDir.resolve("missing/zolt-0.1.1-linux-x64.tar.gz"),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        Path workDirectory = tempDir.resolve("install-work-already");

        NativeVersionInstallResult result = installService.install(new NativeVersionInstallRequest(
                installed.installRoot(),
                installed.binLink(),
                index.toUri(),
                "0.1.1",
                ReleaseTarget.LINUX_X64,
                workDirectory));

        assertFalse(result.installed());
        assertFalse(Files.exists(workDirectory));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void missingRequestedVersionFailsWithoutChangingCurrentSymlink() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path index = writeReleaseIndex("stable", "0.1.1", "linux-x64", archive, "sidecar");

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> installService.install(new NativeVersionInstallRequest(
                        installed.installRoot(),
                        installed.binLink(),
                        index.toUri(),
                        "0.1.2",
                        ReleaseTarget.LINUX_X64,
                        tempDir.resolve("install-work-missing"))));

        assertTrue(exception.getMessage().contains("does not include native Zolt version `0.1.2`"));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    private Path writeReleaseIndex(
            String channel,
            String version,
            String target,
            Path archive,
            String checksum) throws IOException {
        String checksumField = checksum.equals("sidecar")
                ? "\"checksumUrl\": \"" + archive.resolveSibling(archive.getFileName() + ".sha256").toUri() + "\","
                : "\"sha256\": \"" + checksum + "\",";
        Path index = tempDir.resolve("release-index-" + version + "-" + target + ".json");
        Files.writeString(index, """
                {
                  "schemaVersion": 1,
                  "channel": "%s",
                  "updatedAt": "2026-07-07T00:00:00Z",
                  "versions": [
                    {
                      "version": "%s",
                      "commit": "0123456789abcdef0123456789abcdef01234567",
                      "createdAt": "2026-07-07T00:00:00Z",
                      "artifacts": [
                        {
                          "target": "%s",
                          "archive": "%s",
                          "archiveUrl": "%s",
                          %s
                          "format": "tar.gz",
                          "binaryName": "zolt"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(channel, version, target, archive.getFileName(), archive.toUri(), checksumField));
        return index;
    }
}
