package sh.zolt.release.update;

import sh.zolt.release.ReleaseTarget;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.io.TempDir;

abstract class NativeUpdateNoticeServiceTestCase {
    @TempDir
    protected Path tempDir;

    protected final NativeUpdateNoticeService service = new NativeUpdateNoticeService();
    protected final Instant now = Instant.parse("2026-06-28T00:00:00Z");

    protected NativeUpdateNoticeRequest request(InstalledFixture installed, URI channel, Instant time) {
        return request(installed, channel, time, tempDir.resolve("state"));
    }

    protected NativeUpdateNoticeRequest request(
            InstalledFixture installed,
            URI channel,
            Instant time,
            Path stateDirectory) {
        return request(installed, channel, time, stateDirectory, Duration.ZERO);
    }

    protected NativeUpdateNoticeRequest request(
            InstalledFixture installed,
            URI channel,
            Instant time,
            Path stateDirectory,
            Duration interval) {
        return new NativeUpdateNoticeRequest(
                installed.installRoot(),
                installed.binLink(),
                channel,
                ReleaseTarget.LINUX_X64,
                stateDirectory,
                time,
                interval,
                false,
                false,
                false,
                true);
    }

    protected NativeUpdateNoticeRequest request(
            InstalledFixture installed,
            URI channel,
            Instant time,
            boolean disabled,
            boolean offline,
            boolean ci,
            boolean interactive) {
        return new NativeUpdateNoticeRequest(
                installed.installRoot(),
                installed.binLink(),
                channel,
                ReleaseTarget.LINUX_X64,
                tempDir.resolve("state"),
                time,
                Duration.ZERO,
                disabled,
                offline,
                ci,
                interactive);
    }

    protected InstalledFixture install(String version) throws IOException {
        Path installRoot = tempDir.resolve("home/.zolt");
        Path executable = installRoot.resolve("versions").resolve(version).resolve("bin/zolt");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "native");
        executable.toFile().setExecutable(true);
        Path bin = installRoot.resolve("bin");
        Files.createDirectories(bin);
        Path binLink = bin.resolve("zolt");
        Files.deleteIfExists(binLink);
        Files.createSymbolicLink(binLink, Path.of("../versions", version, "bin", "zolt"));
        return new InstalledFixture(installRoot, binLink);
    }

    protected Path writeChannel(String channel, String version) throws IOException {
        Path path = tempDir.resolve(channel + "-" + version + ".json");
        String releaseTag = switch (channel) {
            case "stable" -> "zolt-v" + version;
            case "preview" -> "zolt-preview-v" + version;
            case "zap" -> "zolt-zap-" + version;
            default -> throw new IllegalArgumentException(channel);
        };
        String origin = "https://github.com/zoltsh/releases/releases/download/"
                + releaseTag
                + "/";
        Files.writeString(path, """
                {
                  "schemaVersion": 1,
                  "channel": "%s",
                  "version": "%s",
                  "commit": "0123456789abcdef0123456789abcdef01234567",
                  "createdAt": "2026-06-28T00:00:00Z",
                  "artifacts": [
                    {
                      "target": "linux-x64",
                      "archive": "zolt-%s-linux-x64.tar.gz",
                      "archiveUrl": "%szolt-%s-linux-x64.tar.gz",
                      "checksumUrl": "%szolt-%s-linux-x64.tar.gz.sha256",
                      "format": "tar.gz",
                      "binaryName": "zolt"
                    }
                  ]
                }
                """.formatted(
                        channel,
                        version,
                        version,
                        origin,
                        version,
                        origin,
                        version));
        return path;
    }

    protected static void writeCache(
            Path stateDirectory,
            URI channelUri,
            String target,
            String channel,
            String latestVersion,
            String lastCheckedAt) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("lastCheckedAt", lastCheckedAt);
        properties.setProperty("channelUri", channelUri.toString());
        properties.setProperty("target", target);
        properties.setProperty("channel", channel);
        properties.setProperty("latestVersion", latestVersion);
        Files.createDirectories(stateDirectory);
        try (var output = Files.newOutputStream(stateDirectory.resolve("update-check.properties"))) {
            properties.store(output, "test cache");
        }
    }

    protected record InstalledFixture(Path installRoot, Path binLink) {
    }
}
