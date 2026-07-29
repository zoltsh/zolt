package sh.zolt.build.packaging;

import sh.zolt.build.PackageException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipException;

public final class PackageArchiveWriter implements AutoCloseable {
    private static final long DETERMINISTIC_ENTRY_TIME = 0L;

    private final Path archivePath;
    private final Path temporaryPath;
    private final JarOutputStream jarOutput;
    private final Set<String> directoryEntries = new LinkedHashSet<>();
    private boolean committed;
    private boolean closed;

    private PackageArchiveWriter(Path archivePath) throws IOException {
        this.archivePath = archivePath.toAbsolutePath().normalize();
        this.temporaryPath = Files.createTempFile(
                this.archivePath.getParent(),
                "." + this.archivePath.getFileName() + "-",
                ".tmp");
        this.jarOutput = new JarOutputStream(Files.newOutputStream(temporaryPath));
    }

    public static PackageArchiveWriter open(Path archivePath) throws IOException {
        return new PackageArchiveWriter(archivePath);
    }

    public static void writeJarFromFiles(Path jarPath, Path root, List<Path> files) throws IOException {
        try (PackageArchiveWriter archive = open(jarPath)) {
            List<Path> sortedFiles = files.stream()
                    .sorted(Comparator.comparing(file -> entryName(root, file)))
                    .toList();
            for (Path file : sortedFiles) {
                archive.writeFile(entryName(root, file), file);
            }
            archive.commit();
        }
    }

    public static void writeStringAtomically(
            Path path,
            String content,
            Charset charset) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (Files.isRegularFile(target)
                && content.equals(Files.readString(target, charset))) {
            return;
        }
        Path temporary = Files.createTempFile(
                target.getParent(),
                "." + target.getFileName() + "-",
                ".tmp");
        try {
            Files.writeString(temporary, content, charset);
            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void writeEntry(String name, byte[] content) throws IOException {
        try {
            JarEntry entry = new JarEntry(name);
            entry.setTime(DETERMINISTIC_ENTRY_TIME);
            jarOutput.putNextEntry(entry);
            jarOutput.write(content);
            jarOutput.closeEntry();
        } catch (ZipException exception) {
            throw new PackageException(
                    "Duplicate jar entry `"
                            + name
                            + "`. Remove or rename the duplicate resource and try packaging again.",
                    exception);
        }
    }

    public void writeFile(String name, Path file) throws IOException {
        writeEntry(name, Files.readAllBytes(file));
    }

    public void writeDirectory(String name) throws IOException {
        if (!directoryEntries.add(name)) {
            return;
        }
        try {
            JarEntry entry = new JarEntry(name);
            entry.setTime(DETERMINISTIC_ENTRY_TIME);
            jarOutput.putNextEntry(entry);
            jarOutput.closeEntry();
        } catch (ZipException exception) {
            throw new PackageException(
                    "Duplicate jar entry `"
                            + name
                            + "`. Check the package layout and try again.",
                    exception);
        }
    }

    public void writeParentDirectories(String entryName) throws IOException {
        int slash = entryName.indexOf('/');
        while (slash >= 0) {
            writeDirectory(entryName.substring(0, slash + 1));
            slash = entryName.indexOf('/', slash + 1);
        }
    }

    public void writeStoredEntry(String name, byte[] content) throws IOException {
        try {
            JarEntry entry = new JarEntry(name);
            entry.setTime(DETERMINISTIC_ENTRY_TIME);
            entry.setMethod(JarEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            CRC32 crc = new CRC32();
            crc.update(content);
            entry.setCrc(crc.getValue());
            jarOutput.putNextEntry(entry);
            jarOutput.write(content);
            jarOutput.closeEntry();
        } catch (ZipException exception) {
            throw new PackageException(
                    "Duplicate jar entry `"
                            + name
                            + "`. Remove or rename the duplicate dependency and try packaging again.",
                    exception);
        }
    }

    public void commit() throws IOException {
        if (committed) {
            return;
        }
        closeArchive();
        replace(temporaryPath, archivePath);
        committed = true;
    }

    @Override
    public void close() throws IOException {
        try {
            closeArchive();
        } finally {
            if (!committed) {
                Files.deleteIfExists(temporaryPath);
            }
        }
    }

    private void closeArchive() throws IOException {
        if (!closed) {
            closed = true;
            jarOutput.close();
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String entryName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
