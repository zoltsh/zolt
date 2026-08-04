package sh.zolt.cli.update;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UpdateCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void updateRefusesUnsupportedDevelopmentLayout() throws IOException {
        Path installRoot = tempDir.resolve("home/.zolt");
        Path channel = writeChannel("0.1.0", "linux-x64", archive("0.1.0", "linux-x64", "0.1.0"), "sidecar");
        Path devExecutable = tempDir.resolve("dev/zolt");
        Files.createDirectories(devExecutable.getParent());
        Files.writeString(devExecutable, "dev");

        CommandResult result = execute(
                "self",
                "update",
                "--install-root", installRoot.toString(),
                "--current-executable", devExecutable.toString(),
                "--channel-url", channel.toUri().toString(),
                "--target", "linux-x64");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Installer-managed native Zolt layouts"));
    }

    @Test
    void updateReportsCurrentVersionWhenChannelMatchesInstalledVersion() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("0.1.0", "linux-x64", archive("0.1.0", "linux-x64", "0.1.0"), "sidecar");

        CommandResult result = update(installed, channel);

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Zolt is already current at 0.1.0"));
        assertTrue(result.stdout().contains("stable channel"));
        assertTrue(result.stdout().contains("linux-x64"));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void updateInstallsNewNativeVersionAndSwitchesCurrentSymlink() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("0.1.1", "linux-x64", archive("0.1.1", "linux-x64", "0.1.1"), "sidecar");

        CommandResult result = update(installed, channel);

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Updated native Zolt to 0.1.1"));
        assertTrue(result.stdout().contains("from 0.1.0"));
        assertEquals("../versions/0.1.1/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertTrue(Files.isExecutable(installed.installRoot().resolve("versions/0.1.1/bin/zolt")));
    }

    @Test
    void updateUsesInstalledChannelUrlWhenNoChannelUrlIsProvided() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel(
                "preview",
                "0.1.0-alpha.2",
                "linux-x64",
                archive("0.1.0-alpha.2", "linux-x64", "0.1.0-alpha.2"),
                "sidecar");
        Files.writeString(installed.installRoot().resolve("channel-url"), channel.toUri().toString());

        CommandResult result = execute(
                "self",
                "update",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString(),
                "--target", "linux-x64",
                "--work-dir", tempDir.resolve("update-work").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Updated native Zolt to 0.1.0-alpha.2"));
        assertTrue(result.stdout().contains("preview channel"));
        assertEquals(
                "../versions/0.1.0-alpha.2/bin/zolt",
                Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void updateRejectsInstalledInsecureChannelUrlBeforeFetch() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Files.writeString(installed.installRoot().resolve("channel-url"), "http://dist.example/channel.json");

        CommandResult result = execute(
                "self",
                "update",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString(),
                "--target", "linux-x64",
                "--work-dir", tempDir.resolve("update-work").toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Release channel URL must be an HTTPS URL with a host."));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void updateFailsOnChecksumMismatchBeforeSwitchingVersion() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("0.1.1", "linux-x64", archive("0.1.1", "linux-x64", "0.1.1"), "0".repeat(64));

        CommandResult result = update(installed, channel);

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Checksum mismatch for native Zolt archive"));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void updateFailsWhenChannelDoesNotContainCurrentTarget() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("0.1.1", "linux-arm64", archive("0.1.1", "linux-arm64", "0.1.1"), "sidecar");

        CommandResult result = update(installed, channel);

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("does not include native archive target `linux-x64`"));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void updateSmokeFailureKeepsPreviousVersionCurrent() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("0.1.1", "linux-x64", archive("0.1.1", "linux-x64", "not-0.1.1"), "sidecar");

        CommandResult result = update(installed, channel);

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("failed smoke verification"));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void selfUpdateInstallsNewNativeVersionAndSwitchesCurrentSymlink() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("0.1.1", "linux-x64", archive("0.1.1", "linux-x64", "0.1.1"), "sidecar");

        CommandResult result = execute(
                "self",
                "update",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString(),
                "--channel-url", channel.toUri().toString(),
                "--target", "linux-x64",
                "--work-dir", tempDir.resolve("self-update-work").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Updated native Zolt to 0.1.1"));
        assertTrue(result.stdout().contains("from 0.1.0"));
        assertEquals("../versions/0.1.1/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertEquals("0.1.0", Files.readString(installed.installRoot().resolve("previous-version")).strip());
    }

    @Test
    void selfInstallDownloadsVersionWithoutSwitchingCurrentSymlink() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path archive = archive("0.1.1", "linux-x64", "0.1.1");
        Path index = writeReleaseIndex("stable", "0.1.1", "linux-x64", archive, "sidecar");

        CommandResult result = execute(
                "self",
                "install",
                "0.1.1",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString(),
                "--release-index-url", index.toUri().toString(),
                "--target", "linux-x64",
                "--work-dir", tempDir.resolve("self-install-work").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("✔ Installed native Zolt 0.1.1"));
        assertTrue(result.stdout().contains("stable channel"));
        assertTrue(result.stdout().contains("linux-x64"));
        assertTrue(result.stdout().contains("Run `zolt self use 0.1.1`"));
        assertTrue(Files.isExecutable(installed.installRoot().resolve("versions/0.1.1/bin/zolt")));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertFalse(Files.exists(installed.installRoot().resolve("previous-version")));
    }

    @Test
    void successfulCommandPrintsUpdateAvailableNoticeWhenForced() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("0.1.1", "linux-x64", archive("0.1.1", "linux-x64", "0.1.1"), "sidecar");

        CommandResult result = execute(
                "--update-check", "always",
                "--internal-enable-update-notices",
                "--update-check-install-root", installed.installRoot().toString(),
                "--update-check-current-executable", installed.binLink().toString(),
                "--update-check-channel-url", channel.toUri().toString(),
                "--update-check-target", "linux-x64",
                "--update-check-state-dir", tempDir.resolve("notice-state").toString(),
                "version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("0.1.0-SNAPSHOT"));
        assertTrue(result.stderr().contains(
                "A newer Zolt is available on stable: 0.1.0 -> 0.1.1. Run `zolt self update` to download and verify it."));
    }

    @Test
    void forcedUpdateAvailableNoticeIsSuppressedWithoutInternalFlag() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("0.1.1", "linux-x64", archive("0.1.1", "linux-x64", "0.1.1"), "sidecar");

        CommandResult result = execute(
                "--update-check", "always",
                "--update-check-install-root", installed.installRoot().toString(),
                "--update-check-current-executable", installed.binLink().toString(),
                "--update-check-channel-url", channel.toUri().toString(),
                "--update-check-target", "linux-x64",
                "--update-check-state-dir", tempDir.resolve("notice-state").toString(),
                "version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("0.1.0-SNAPSHOT"));
        assertEquals("", result.stderr());
    }

    @Test
    void updateAvailableNoticeUsesInstalledPreviewChannelUrl() throws IOException {
        InstalledFixture installed = install("0.1.0-alpha.1");
        Path channel = writeChannel(
                "preview",
                "0.1.0-alpha.2",
                "linux-x64",
                archive("0.1.0-alpha.2", "linux-x64", "0.1.0-alpha.2"),
                "sidecar");
        Files.writeString(installed.installRoot().resolve("channel-url"), channel.toUri().toString());

        CommandResult result = execute(
                "--update-check", "always",
                "--internal-enable-update-notices",
                "--update-check-install-root", installed.installRoot().toString(),
                "--update-check-current-executable", installed.binLink().toString(),
                "--update-check-target", "linux-x64",
                "--update-check-state-dir", tempDir.resolve("notice-state").toString(),
                "version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stderr().contains(
                "A newer Zolt is available on preview: 0.1.0-alpha.1 -> 0.1.0-alpha.2. Run `zolt self update` to download and verify it."));
    }

    @Test
    void updateAvailableNoticeIsQuietByDefaultInNonInteractiveOutput() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path channel = writeChannel("0.1.1", "linux-x64", archive("0.1.1", "linux-x64", "0.1.1"), "sidecar");

        CommandResult result = execute(
                "--update-check-install-root", installed.installRoot().toString(),
                "--update-check-current-executable", installed.binLink().toString(),
                "--update-check-channel-url", channel.toUri().toString(),
                "--update-check-target", "linux-x64",
                "--update-check-state-dir", tempDir.resolve("notice-state").toString(),
                "version");

        assertEquals(0, result.exitCode());
        assertEquals("", result.stderr());
    }

    @Test
    void updateAvailableNoticeDoesNotFailOriginalCommandOnBadChannel() throws IOException {
        InstalledFixture installed = install("0.1.0");

        CommandResult result = execute(
                "--update-check", "always",
                "--internal-enable-update-notices",
                "--update-check-install-root", installed.installRoot().toString(),
                "--update-check-current-executable", installed.binLink().toString(),
                "--update-check-channel-url", tempDir.resolve("missing-channel.json").toUri().toString(),
                "--update-check-target", "linux-x64",
                "--update-check-state-dir", tempDir.resolve("notice-state").toString(),
                "version");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("0.1.0-SNAPSHOT"));
        assertEquals("", result.stderr());
    }

    private CommandResult update(InstalledFixture installed, Path channel) {
        return execute(
                "self",
                "update",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString(),
                "--channel-url", channel.toUri().toString(),
                "--target", "linux-x64",
                "--work-dir", tempDir.resolve("update-work").toString());
    }

    private InstalledFixture install(String version) throws IOException {
        Path installRoot = tempDir.resolve("home/.zolt");
        Path executable = installRoot.resolve("versions").resolve(version).resolve("bin/zolt");
        writeFakeZolt(executable, version);
        Path bin = installRoot.resolve("bin");
        Files.createDirectories(bin);
        Path binLink = bin.resolve("zolt");
        Files.deleteIfExists(binLink);
        Files.createSymbolicLink(binLink, Path.of("../versions", version, "bin", "zolt"));
        return new InstalledFixture(installRoot, binLink);
    }

    private Path archive(String version, String target, String binaryVersion) throws IOException {
        Path root = tempDir.resolve("archives");
        Path archive = root.resolve("zolt-" + version + "-" + target + ".tar.gz");
        Path packageDir = tempDir.resolve("package/zolt-" + version + "-" + target);
        deleteIfExists(tempDir.resolve("package"));
        writeFakeZolt(packageDir.resolve("bin/zolt"), binaryVersion);
        Files.createDirectories(root);
        try {
            Process process = new ProcessBuilder("tar", "-C", tempDir.resolve("package").toString(), "-czf", archive.toString(), packageDir.getFileName().toString())
                    .redirectErrorStream(true)
                    .start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("tar failed: " + new String(process.getInputStream().readAllBytes()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("tar interrupted", exception);
        }
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"), sha256(archive) + "  " + archive.getFileName() + "\n");
        return archive;
    }

    private Path writeChannel(String version, String target, Path archive, String checksum) throws IOException {
        return writeChannel("stable", version, target, archive, checksum);
    }

    private Path writeChannel(String channelName, String version, String target, Path archive, String checksum) throws IOException {
        String checksumField = checksum.equals("sidecar")
                ? "\"checksumUrl\": \"" + archive.resolveSibling(archive.getFileName() + ".sha256").toUri() + "\","
                : "\"sha256\": \"" + checksum + "\",";
        Path channel = tempDir.resolve("channel-" + version + "-" + target + ".json");
        Files.writeString(channel, """
                {
                  "schemaVersion": 1,
                  "channel": "%s",
                  "version": "%s",
                  "commit": "0123456789abcdef0123456789abcdef01234567",
                  "createdAt": "2026-06-28T00:00:00Z",
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
                """.formatted(channelName, version, target, archive.getFileName(), archive.toUri(), checksumField));
        return channel;
    }

    private Path writeReleaseIndex(String channelName, String version, String target, Path archive, String checksum) throws IOException {
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
                """.formatted(channelName, version, target, archive.getFileName(), archive.toUri(), checksumField));
        return index;
    }

    private static void writeFakeZolt(Path executable, String version) throws IOException {
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, """
                #!/usr/bin/env bash
                set -euo pipefail
                if [[ "${1:-}" == "--version" ]]; then
                  printf '%s\\n' "%s"
                  exit 0
                fi
                exit 0
                """.formatted("%s", version));
        executable.toFile().setExecutable(true);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private record InstalledFixture(Path installRoot, Path binLink) {
    }
}
