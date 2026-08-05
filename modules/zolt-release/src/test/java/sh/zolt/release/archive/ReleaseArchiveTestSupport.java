package sh.zolt.release.archive;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

abstract class ReleaseArchiveTestSupport {
    @TempDir
    protected Path projectDir;

    protected void writeProjectFiles() throws IOException {
        Files.writeString(projectDir.resolve("README.md"), "# Demo\n");
        writeLegalDocuments(projectDir);
    }

    protected static void writeLegalDocuments(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("LICENSE"), "license\n");
        Files.writeString(directory.resolve("NOTICE"), "notice\n");
        Files.writeString(directory.resolve("THIRD_PARTY_NOTICES"), "third party notices\n");
    }

    protected Path writeBinary(String path) throws IOException {
        Path binary = projectDir.resolve(path);
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "native");
        return Path.of(path);
    }

    protected Path writeWorkerJar(String path) throws IOException {
        Path workerJar = projectDir.resolve(path).toAbsolutePath().normalize();
        Files.createDirectories(workerJar.getParent());
        Files.writeString(workerJar, "worker");
        return workerJar;
    }

    protected static ProjectConfig config() {
        return config(new ProjectMetadata(
                "zolt",
                "0.1.0",
                "sh.zolt",
                currentJavaMajorVersion(),
                Optional.of("sh.zolt.Main")));
    }

    protected static ProjectConfig config(ProjectMetadata projectMetadata) {
        return ProjectConfigs.withDirectDependencies(
                projectMetadata,
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults(),
                new NativeSettings("zolt", "target/native", List.of("--no-fallback")));
    }

    protected static String versionFileContent(Path archivePath, String rootDirectory) throws IOException {
        return tarEntryContent(archivePath, rootDirectory + "/VERSION");
    }

    protected static String tarEntryContent(Path archivePath, String target) throws IOException {
        byte[] content;
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(archivePath))) {
            content = input.readAllBytes();
        }
        int offset = 0;
        while (offset + 512 <= content.length) {
            byte[] header = java.util.Arrays.copyOfRange(content, offset, offset + 512);
            if (allZero(header)) {
                break;
            }
            String name = readNullTerminated(header, 0, 100);
            long size = Long.parseLong(readNullTerminated(header, 124, 12).trim(), 8);
            if (name.equals(target)) {
                return new String(
                        java.util.Arrays.copyOfRange(content, offset + 512, offset + 512 + (int) size),
                        StandardCharsets.UTF_8);
            }
            offset += 512 + paddedSize(size);
        }
        throw new IOException("tar entry " + target + " not found in " + archivePath);
    }

    protected static List<String> tarEntries(Path archivePath) throws IOException {
        byte[] content;
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(archivePath))) {
            content = input.readAllBytes();
        }
        List<String> entries = new ArrayList<>();
        int offset = 0;
        while (offset + 512 <= content.length) {
            byte[] header = java.util.Arrays.copyOfRange(content, offset, offset + 512);
            if (allZero(header)) {
                break;
            }
            String name = readNullTerminated(header, 0, 100);
            long size = Long.parseLong(readNullTerminated(header, 124, 12).trim(), 8);
            entries.add(name);
            offset += 512 + paddedSize(size);
        }
        return entries;
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static String readNullTerminated(byte[] bytes, int offset, int length) throws IOException {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) {
            end++;
        }
        return new String(new ByteArrayInputStream(bytes, offset, end - offset).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static int paddedSize(long size) {
        long remainder = size % 512;
        return (int) (remainder == 0 ? size : size + 512 - remainder);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }

    protected static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
