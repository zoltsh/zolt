package sh.zolt.toolchain.install;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.toolchain.catalog.JavaToolchainArchiveFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavaToolchainArchiveExtractorTest {
    @TempDir
    private Path tempDir;

    private final JavaToolchainArchiveExtractor extractor = new JavaToolchainArchiveExtractor();

    @Test
    void extractsTarGzAndStripsTopLevelDirectory() throws IOException {
        Path archive = tempDir.resolve("jdk.tar.gz");
        writeTarGz(
                archive,
                entry("jdk/bin/java", "java", 0755),
                entry("jdk/bin/javac", "javac", 0755),
                entry("jdk/bin/jar", "jar", 0755));
        Path destination = tempDir.resolve("out");

        extractor.extract(archive, JavaToolchainArchiveFormat.TAR_GZ, destination, true);

        assertTrue(Files.isExecutable(destination.resolve("bin/java")));
        assertTrue(Files.readString(destination.resolve("bin/javac")).contains("javac"));
        assertTrue(Files.exists(destination.resolve("bin/jar")));
    }

    @Test
    void extractsPaxPathHeaders() throws IOException {
        Path archive = tempDir.resolve("pax.tar.gz");
        writeTarGz(
                archive,
                paxEntry(
                        "jdk/Contents/Home/conf/security/policy/unlimited/default_local.pol",
                        "jdk/Contents/Home/conf/security/policy/unlimited/default_local.policy",
                        "policy",
                        0644));
        Path destination = tempDir.resolve("out");

        extractor.extract(archive, JavaToolchainArchiveFormat.TAR_GZ, destination, true);

        assertTrue(Files.exists(destination.resolve(
                "Contents/Home/conf/security/policy/unlimited/default_local.policy")));
        assertTrue(Files.notExists(destination.resolve(
                "Contents/Home/conf/security/policy/unlimited/default_local.pol")));
    }

    @Test
    void extractsSafeTarSymlinks() throws IOException {
        Path archive = tempDir.resolve("symlink.tar.gz");
        writeTarGz(
                archive,
                entry("jdk/lib/svm/bin/native-image", "native-image", 0755),
                symlink("jdk/bin/native-image", "../lib/svm/bin/native-image"));
        Path destination = tempDir.resolve("out");

        extractor.extract(archive, JavaToolchainArchiveFormat.TAR_GZ, destination, true);

        assertTrue(Files.isSymbolicLink(destination.resolve("bin/native-image")));
        assertTrue(Files.exists(destination.resolve("bin/native-image")));
    }

    @Test
    void rejectsUnsafeTarPaths() throws IOException {
        Path archive = tempDir.resolve("unsafe.tar.gz");
        writeTarGz(archive, entry("jdk/../../escape", "nope", 0644));

        ActionableException exception = assertThrows(ActionableException.class, () ->
                extractor.extract(archive, JavaToolchainArchiveFormat.TAR_GZ, tempDir.resolve("out"), true));

        assertTrue(exception.getMessage().contains("unsafe path"));
    }

    @Test
    void rejectsUnsafeTarSymlinks() throws IOException {
        Path archive = tempDir.resolve("unsafe-symlink.tar.gz");
        writeTarGz(archive, symlink("jdk/bin/java", "../../../escape"));

        ActionableException exception = assertThrows(ActionableException.class, () ->
                extractor.extract(archive, JavaToolchainArchiveFormat.TAR_GZ, tempDir.resolve("out"), true));

        assertTrue(exception.getMessage().contains("unsafe symlink"));
    }

    @Test
    void rejectsWritesThroughExistingEscapingSymlinkAncestors() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("java"), "preserve me");
        Path destination = Files.createDirectory(tempDir.resolve("out"));
        Files.createSymbolicLink(destination.resolve("bin"), outside);
        Path archive = tempDir.resolve("symlink-parent.tar.gz");
        writeTarGz(archive, entry("jdk/bin/java", "overwrite", 0755));

        assertThrows(ActionableException.class, () ->
                extractor.extract(archive, JavaToolchainArchiveFormat.TAR_GZ, destination, true));

        org.junit.jupiter.api.Assertions.assertEquals("preserve me", Files.readString(outside.resolve("java")));
    }

    @Test
    void rejectsParentTraversalAfterAnEarlierConfinedSymlink() throws IOException {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("sentinel"), "preserve me");
        Path archive = tempDir.resolve("symlink-parent-traversal.tar.gz");
        writeTarGz(archive,
                symlink("jdk/root-alias", "."),
                symlink("jdk/escape", "root-alias/../outside"),
                entry("jdk/escape/sentinel", "overwrite", 0644));

        assertThrows(ActionableException.class, () ->
                extractor.extract(archive, JavaToolchainArchiveFormat.TAR_GZ, tempDir.resolve("out"), true));

        org.junit.jupiter.api.Assertions.assertEquals("preserve me", Files.readString(outside.resolve("sentinel")));
    }

    @Test
    void acceptsConfinedSymlinksWhoseTargetsOccurLaterInTheArchive() throws IOException {
        Path archive = tempDir.resolve("forward-symlink.tar.gz");
        writeTarGz(archive,
                symlink("jdk/bin/native-image", "../lib/svm/bin/native-image"),
                entry("jdk/lib/svm/bin/native-image", "native-image", 0755));
        Path destination = tempDir.resolve("out");

        extractor.extract(archive, JavaToolchainArchiveFormat.TAR_GZ, destination, true);

        assertTrue(Files.isSymbolicLink(destination.resolve("bin/native-image")));
        assertTrue(Files.isExecutable(destination.resolve("bin/native-image")));
    }

    private static TarEntry entry(String name, String content, int mode) {
        return new TarEntry(
                name,
                Optional.empty(),
                content.getBytes(StandardCharsets.UTF_8),
                mode,
                (byte) '0',
                "");
    }

    private static TarEntry paxEntry(String headerName, String paxPath, String content, int mode) {
        return new TarEntry(
                headerName,
                Optional.of(paxPath),
                content.getBytes(StandardCharsets.UTF_8),
                mode,
                (byte) '0',
                "");
    }

    private static TarEntry symlink(String name, String linkName) {
        return new TarEntry(
                name,
                Optional.empty(),
                new byte[0],
                0644,
                (byte) '2',
                linkName);
    }

    private static void writeTarGz(Path archive, TarEntry... entries) throws IOException {
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(archive))) {
            for (TarEntry entry : entries) {
                if (entry.paxPath().isPresent()) {
                    byte[] pax = paxRecord("path", entry.paxPath().orElseThrow());
                    writeEntry(output, new TarEntry(
                            "PaxHeaders/path",
                            Optional.empty(),
                            pax,
                            0644,
                            (byte) 'x',
                            ""));
                }
                writeEntry(output, entry);
            }
            output.write(new byte[1024]);
        }
    }

    private static void writeEntry(GZIPOutputStream output, TarEntry entry) throws IOException {
        writeHeader(output, entry.name(), entry.content(), entry.mode(), entry.type(), entry.linkName());
        output.write(entry.content());
        int padding = (int) ((512 - (entry.content().length % 512)) % 512);
        output.write(new byte[padding]);
    }

    private static void writeHeader(
            GZIPOutputStream output,
            String name,
            byte[] content,
            int mode,
            byte type,
            String linkName) throws IOException {
        byte[] header = new byte[512];
        putString(header, 0, 100, name);
        putOctal(header, 100, 8, mode);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, content.length);
        putOctal(header, 136, 12, 0);
        header[156] = type;
        putString(header, 157, 100, linkName);
        putString(header, 257, 6, "ustar");
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        long checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        putOctal(header, 148, 8, checksum);
        output.write(header);
    }

    private static byte[] paxRecord(String key, String value) {
        String payload = key + "=" + value + "\n";
        int length = payload.length() + 2;
        while (true) {
            String record = length + " " + payload;
            if (record.length() == length) {
                return record.getBytes(StandardCharsets.UTF_8);
            }
            length = record.length();
        }
    }

    private static void putString(byte[] header, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length));
    }

    private static void putOctal(byte[] header, int offset, int length, long value) {
        byte[] bytes = Long.toOctalString(value).getBytes(StandardCharsets.UTF_8);
        int start = offset + length - bytes.length - 1;
        System.arraycopy(bytes, 0, header, start, bytes.length);
        header[offset + length - 1] = 0;
    }

    private record TarEntry(
            String name,
            Optional<String> paxPath,
            byte[] content,
            int mode,
            byte type,
            String linkName) {
    }
}
