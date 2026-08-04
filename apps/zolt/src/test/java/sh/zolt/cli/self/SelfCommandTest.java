package sh.zolt.cli.self;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SelfCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void versionsListsInstalledNativeVersionsAndCurrentMarker() throws IOException {
        InstalledFixture installed = install("0.1.0");
        writeFakeZolt(installed.installRoot().resolve("versions/0.1.1/bin/zolt"), "0.1.1");

        CommandResult result = execute(
                "self",
                "versions",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Installed native Zolt versions"));
        assertTrue(result.stdout().contains("current 0.1.0"));
        assertTrue(result.stdout().contains("* 0.1.0 current"));
        assertTrue(result.stdout().contains("  0.1.1"));
    }

    @Test
    void versionsInfersCustomInstallRootFromCurrentExecutable() throws IOException {
        InstalledFixture installed = install("0.1.0");

        CommandResult result = execute(
                "self",
                "versions",
                "--current-executable", installed.binLink().toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("current 0.1.0"));
        assertTrue(result.stdout().contains("* 0.1.0 current"));
    }

    @Test
    void useSwitchesToInstalledVersion() throws IOException {
        InstalledFixture installed = install("0.1.0");
        writeFakeZolt(installed.installRoot().resolve("versions/0.1.1/bin/zolt"), "0.1.1");

        CommandResult result = execute(
                "self",
                "use",
                "0.1.1",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Switched native Zolt to 0.1.1"));
        assertTrue(result.stdout().contains("from 0.1.0"));
        assertEquals("../versions/0.1.1/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertEquals("0.1.0", Files.readString(installed.installRoot().resolve("previous-version")).strip());
    }

    @Test
    void rollbackSwitchesToRecordedPreviousVersion() throws IOException {
        InstalledFixture installed = install("0.1.0");
        writeFakeZolt(installed.installRoot().resolve("versions/0.1.1/bin/zolt"), "0.1.1");
        execute(
                "self",
                "use",
                "0.1.1",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString());

        CommandResult result = execute(
                "self",
                "rollback",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Rolled native Zolt back to 0.1.0"));
        assertTrue(result.stdout().contains("from 0.1.1"));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
        assertEquals("0.1.1", Files.readString(installed.installRoot().resolve("previous-version")).strip());
    }

    @Test
    void rollbackFailsWithoutRecordedPreviousVersion() throws IOException {
        InstalledFixture installed = install("0.1.0");

        CommandResult result = execute(
                "self",
                "rollback",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("No previous native Zolt version is recorded"));
        assertEquals("../versions/0.1.0/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void pruneRemovesOldInstalledVersionsAndPreservesRollbackVersion() throws IOException {
        InstalledFixture installed = install("0.1.0");
        writeFakeZolt(installed.installRoot().resolve("versions/0.1.1/bin/zolt"), "0.1.1");
        writeFakeZolt(installed.installRoot().resolve("versions/0.1.2/bin/zolt"), "0.1.2");
        writeFakeZolt(installed.installRoot().resolve("versions/0.1.3/bin/zolt"), "0.1.3");
        execute(
                "self",
                "use",
                "0.1.1",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString());

        CommandResult result = execute(
                "self",
                "prune",
                "--keep", "3",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Pruned installed native Zolt versions"));
        assertTrue(result.stdout().contains("removed 1"));
        assertTrue(result.stdout().contains("kept 3"));
        assertTrue(result.stdout().contains("previous: 0.1.0"));
        assertTrue(result.stdout().contains("- 0.1.2"));
        assertTrue(Files.exists(installed.installRoot().resolve("versions/0.1.0/bin/zolt")));
        assertTrue(Files.exists(installed.installRoot().resolve("versions/0.1.1/bin/zolt")));
        assertFalse(Files.exists(installed.installRoot().resolve("versions/0.1.2")));
        assertTrue(Files.exists(installed.installRoot().resolve("versions/0.1.3/bin/zolt")));
        assertEquals("../versions/0.1.1/bin/zolt", Files.readSymbolicLink(installed.binLink()).toString());
    }

    @Test
    void pruneDryRunDoesNotDeleteVersions() throws IOException {
        InstalledFixture installed = install("0.1.0");
        writeFakeZolt(installed.installRoot().resolve("versions/0.1.1/bin/zolt"), "0.1.1");

        CommandResult result = execute(
                "self",
                "prune",
                "--keep", "1",
                "--dry-run",
                "--install-root", installed.installRoot().toString(),
                "--current-executable", installed.binLink().toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("✔ Would prune installed native Zolt versions"));
        assertTrue(result.stdout().contains("- 0.1.1"));
        assertTrue(Files.exists(installed.installRoot().resolve("versions/0.1.1/bin/zolt")));
    }

    @Test
    void releasesListsRemoteVersionsFromReleaseIndex() throws IOException {
        Path index = tempDir.resolve("zap-release-index.json");
        Files.writeString(index, releaseIndexJson("0.1.0-zap.20260707.333333333333"));

        CommandResult result = execute(
                "self",
                "releases",
                "--channel", "zap",
                "--release-index-url", index.toUri().toString(),
                "--install-root", tempDir.resolve("home/.zolt").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("✔ Remote native Zolt releases"));
        assertTrue(result.stdout().contains("zap"));
        assertTrue(result.stdout().contains("1 versions"));
        assertTrue(result.stdout().contains("release index: " + index.toUri()));
        assertTrue(result.stdout().contains("- 0.1.0-zap.20260707.333333333333 (2026-07-07T00:00:00Z)"));
        assertTrue(result.stdout().contains("targets: linux-x64"));
    }

    @Test
    void channelShowsAndWritesInstallerChannelState() throws IOException {
        InstalledFixture installed = install("0.1.0");
        Path installRoot = installed.installRoot();

        CommandResult set = execute(
                "self",
                "channel",
                "zap",
                "--install-root", installRoot.toString(),
                "--origin", "https://dist.example.test/zolt");
        CommandResult show = execute(
                "self",
                "channel",
                "--install-root", installRoot.toString(),
                "--origin", "https://dist.example.test/zolt");

        assertEquals(0, set.exitCode());
        assertTrue(set.stdout().contains("✔ Set native Zolt channel to zap"));
        assertEquals("zap", Files.readString(installRoot.resolve("channel")).strip());
        assertEquals("https://dist.example.test/zolt/channels/zap.json", Files.readString(installRoot.resolve("channel-url")).strip());
        assertEquals(0, show.exitCode());
        assertTrue(show.stdout().contains("channel: zap"));
        assertTrue(show.stdout().contains("channel url: https://dist.example.test/zolt/channels/zap.json"));
    }

    @Test
    void channelRejectsUnknownChannel() throws IOException {
        InstalledFixture installed = install("0.1.0");

        CommandResult result = execute(
                "self",
                "channel",
                "experimental",
                "--install-root", installed.installRoot().toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Native Zolt channel must be one of stable, preview, zap"));
        assertFalse(Files.exists(installed.installRoot().resolve("channel")));
        assertFalse(Files.exists(installed.installRoot().resolve("channel-url")));
    }

    private static String releaseIndexJson(String version) {
        return """
                {
                  "schemaVersion": 1,
                  "channel": "zap",
                  "updatedAt": "2026-07-07T00:00:00Z",
                  "versions": [
                    {
                      "version": "%s",
                      "commit": "0123456789abcdef0123456789abcdef01234567",
                      "createdAt": "2026-07-07T00:00:00Z",
                      "artifacts": [
                        {
                          "target": "linux-x64",
                          "archive": "zolt-%s-linux-x64.tar.gz",
                          "archiveUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-%s/zolt-%s-linux-x64.tar.gz",
                          "checksumUrl": "https://github.com/zoltsh/releases/releases/download/zolt-zap-%s/zolt-%s-linux-x64.tar.gz.sha256",
                          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                          "format": "tar.gz",
                          "binaryName": "zolt"
                        }
                      ]
                    }
                  ]
                }
                """.formatted(version, version, version, version, version, version);
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

    private record InstalledFixture(Path installRoot, Path binLink) {
    }
}
