package sh.zolt.release.update;

import sh.zolt.release.ReleaseTarget;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.io.TempDir;

abstract class NativeUpdateServiceTestCase {
    @TempDir
    protected Path tempDir;

    protected final NativeUpdateService service = new NativeUpdateService();

    protected NativeUpdateRequest request(InstalledFixture installed, Path channel, Path workDirectory) {
        return new NativeUpdateRequest(
                installed.installRoot(),
                installed.binLink(),
                channel.toUri(),
                ReleaseTarget.LINUX_X64,
                workDirectory);
    }

    protected InstalledFixture install(String version) throws IOException {
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

    protected Path archive(String version, String target, String binaryVersion) throws IOException {
        Path root = tempDir.resolve("archives");
        Path archive = root.resolve("zolt-" + version + "-" + target + ".tar.gz");
        Path packageDir = tempDir.resolve("package/zolt-" + version + "-" + target);
        deleteIfExists(tempDir.resolve("package"));
        writeFakeZolt(packageDir.resolve("bin/zolt"), binaryVersion);
        Files.createDirectories(root);
        try {
            Process process = new ProcessBuilder(
                            "tar",
                            "-C",
                            tempDir.resolve("package").toString(),
                            "-czf",
                            archive.toString(),
                            packageDir.getFileName().toString())
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

    protected Path tarArchive(String archiveName, TarEntry... entries) throws IOException {
        Path archive = tempDir.resolve("archives").resolve(archiveName);
        Files.createDirectories(archive.getParent());
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(archive))) {
            for (TarEntry entry : entries) {
                writeTarEntry(output, entry);
            }
            output.write(new byte[1024]);
        }
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"), sha256(archive) + "  " + archive.getFileName() + "\n");
        return archive;
    }

    private static void writeTarEntry(OutputStream output, TarEntry entry) throws IOException {
        long size = entry.type() == '0' ? entry.content().length : 0;
        output.write(tarHeader(entry.name(), entry.type(), size));
        if (entry.type() == '0') {
            output.write(entry.content());
            pad(output, size);
        }
    }

    private static byte[] tarHeader(String name, char type, long size) {
        byte[] header = new byte[512];
        writeString(header, 0, 100, name);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 0);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = (byte) type;
        if (type == '1' || type == '2') {
            writeString(header, 157, 100, "../../outside");
        }
        writeString(header, 257, 6, "ustar");
        writeString(header, 263, 2, "00");
        long checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeOctal(header, 148, 8, checksum);
        header[155] = ' ';
        return header;
    }

    private static void writeString(byte[] output, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, output, offset, Math.min(bytes.length, length));
    }

    private static void writeOctal(byte[] output, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        int start = offset + length - octal.length() - 1;
        for (int index = offset; index < start; index++) {
            output[index] = '0';
        }
        byte[] bytes = octal.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, output, start, bytes.length);
        output[offset + length - 1] = 0;
    }

    private static void pad(OutputStream output, long size) throws IOException {
        int padding = (int) (512 - (size % 512));
        if (padding != 512) {
            output.write(new byte[padding]);
        }
    }

    protected Path writeChannel(
            String channelName,
            String version,
            String target,
            Path archive,
            String manifestArchive,
            String checksum) throws IOException {
        String checksumField = checksum.equals("sidecar")
                ? "\"checksumUrl\": \"" + archive.resolveSibling(archive.getFileName() + ".sha256").toUri() + "\","
                : "\"sha256\": \"" + checksum + "\",";
        Path channel = tempDir.resolve("channel-" + channelName + "-" + target + ".json");
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
                """.formatted(channelName, version, target, manifestArchive, archive.toUri(), checksumField));
        return channel;
    }

    protected static void writeFakeZolt(Path executable, String version) throws IOException {
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

    protected static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read = input.read(buffer);
                while (read >= 0) {
                    digest.update(buffer, 0, read);
                    read = input.read(buffer);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    protected record InstalledFixture(Path installRoot, Path binLink) {
    }

    protected record TarEntry(String name, char type, byte[] content) {
        protected static TarEntry file(String name, String content) {
            return new TarEntry(name, '0', content.getBytes(StandardCharsets.UTF_8));
        }

        protected static TarEntry link(String name, char type) {
            return new TarEntry(name, type, new byte[0]);
        }
    }
}
