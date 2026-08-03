package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.PackageException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageArchiveWriterTest {
    @TempDir
    private Path tempDir;

    @Test
    void writesDeterministicEntriesDirectoriesAndStoredDependencies() throws IOException {
        Path archivePath = tempDir.resolve("demo.jar");

        try (PackageArchiveWriter archive = PackageArchiveWriter.open(archivePath)) {
            archive.writeDirectory("BOOT-INF/");
            archive.writeDirectory("BOOT-INF/");
            archive.writeParentDirectories("BOOT-INF/classes/com/example/Main.class");
            archive.writeEntry("BOOT-INF/classes/com/example/Main.class", new byte[] {1, 2, 3});
            archive.writeStoredEntry("BOOT-INF/lib/runtime.jar", new byte[] {4, 5, 6});
            archive.commit();
        }

        try (JarFile jar = new JarFile(archivePath.toFile())) {
            assertNotNull(jar.getEntry("BOOT-INF/"));
            assertNotNull(jar.getEntry("BOOT-INF/classes/"));
            assertNotNull(jar.getEntry("BOOT-INF/classes/com/"));
            assertNotNull(jar.getEntry("BOOT-INF/classes/com/example/"));
            assertEquals(0L, jar.getEntry("BOOT-INF/classes/com/example/Main.class").getTime());
            JarEntry stored = jar.getJarEntry("BOOT-INF/lib/runtime.jar");
            assertEquals(JarEntry.STORED, stored.getMethod());
            assertEquals(0L, stored.getTime());
        }
    }

    @Test
    void writeJarFromFilesUsesRootRelativeSortedEntryNames() throws IOException {
        Path root = tempDir.resolve("classes");
        Path b = root.resolve("com/example/B.class");
        Path a = root.resolve("com/example/A.class");
        Files.createDirectories(a.getParent());
        Files.write(a, new byte[] {1});
        Files.write(b, new byte[] {2});
        Path archivePath = tempDir.resolve("classes.jar");

        PackageArchiveWriter.writeJarFromFiles(archivePath, root, List.of(b, a));

        try (JarFile jar = new JarFile(archivePath.toFile())) {
            assertNotNull(jar.getEntry("com/example/A.class"));
            assertNotNull(jar.getEntry("com/example/B.class"));
            assertEquals(0L, jar.getEntry("com/example/A.class").getTime());
            Enumeration<JarEntry> entries = jar.entries();
            assertEquals("com/example/A.class", entries.nextElement().getName());
            assertEquals("com/example/B.class", entries.nextElement().getName());
        }
    }

    @Test
    void duplicateEntriesFailWithPackageDiagnostic() throws IOException {
        Path archivePath = tempDir.resolve("duplicate.jar");
        byte[] original = new byte[] {7, 8, 9};
        Files.write(archivePath, original);

        PackageException exception = assertThrows(PackageException.class, () -> {
            try (PackageArchiveWriter archive = PackageArchiveWriter.open(archivePath)) {
                archive.writeEntry("com/example/Main.class", new byte[] {1});
                archive.writeEntry("com/example/Main.class", new byte[] {2});
                archive.commit();
            }
        });

        assertEquals(
                "Duplicate jar entry `com/example/Main.class`. Remove or rename the duplicate resource and try packaging again.",
                exception.getMessage());
        assertArrayEquals(original, Files.readAllBytes(archivePath));
        try (var paths = Files.list(tempDir)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void closingWithoutCommitPreservesExistingArchive() throws IOException {
        Path archivePath = tempDir.resolve("existing.jar");
        byte[] original = new byte[] {4, 5, 6};
        Files.write(archivePath, original);

        try (PackageArchiveWriter archive = PackageArchiveWriter.open(archivePath)) {
            archive.writeEntry("com/example/Main.class", new byte[] {1});
        }

        assertArrayEquals(original, Files.readAllBytes(archivePath));
    }

    @Test
    void atomicStringWritePreservesAnUnchangedFile() throws IOException {
        Path output = tempDir.resolve("runtime-classpath");
        PackageArchiveWriter.writeStringAtomically(
                output,
                "one\ntwo\n",
                StandardCharsets.UTF_8);
        FileTime originalTime = FileTime.fromMillis(1_000L);
        Files.setLastModifiedTime(output, originalTime);

        PackageArchiveWriter.writeStringAtomically(
                output,
                "one\ntwo\n",
                StandardCharsets.UTF_8);

        assertEquals(originalTime, Files.getLastModifiedTime(output));
        assertEquals("one\ntwo\n", Files.readString(output));
    }
}
