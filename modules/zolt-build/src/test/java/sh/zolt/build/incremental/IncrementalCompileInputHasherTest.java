package sh.zolt.build.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IncrementalCompileInputHasherTest {
    @TempDir
    private Path tempDir;

    @Test
    void classpathEntryReusesCurrentLockfileVerifiedHash() throws IOException {
        Path jar = tempDir.resolve("dependency.jar");
        Files.writeString(jar, "dependency bytes");

        IncrementalCompileState.ClasspathEntry entry =
                IncrementalCompileInputHasher.classpathEntry(
                        jar,
                        path -> Optional.of("lockfile-verified-hash"));

        assertEquals("lockfile-verified-hash", entry.hash());
        assertEquals(Files.size(jar), entry.size());
    }

    @Test
    void hashesFileContent() throws IOException {
        Path source = tempDir.resolve("src/main/java/com/example/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}\n");

        String first = IncrementalCompileInputHasher.hash(source);
        Files.writeString(source, "class App { void changed() {} }\n");
        String second = IncrementalCompileInputHasher.hash(source);

        assertNotEquals(first, second);
    }

    @Test
    void directoryHashIgnoresLocalCompileMetadata() throws IOException {
        Path output = tempDir.resolve("target/classes");
        Files.createDirectories(output.resolve("com/example"));
        Files.writeString(output.resolve("com/example/App.class"), "app");

        String beforeMetadata = IncrementalCompileInputHasher.hash(output);
        Files.writeString(output.resolve(IncrementalCompileState.MAIN_FILE_NAME), "state");
        Files.writeString(output.resolve(".zolt-build-main.fingerprint"), "fingerprint");
        String afterMetadata = IncrementalCompileInputHasher.hash(output);
        Files.writeString(output.resolve("com/example/Other.class"), "other");
        String afterClass = IncrementalCompileInputHasher.hash(output);

        assertEquals(beforeMetadata, afterMetadata);
        assertNotEquals(afterMetadata, afterClass);
    }

    @Test
    void missingInputHashesAsMissing() {
        assertEquals("missing", IncrementalCompileInputHasher.hash(tempDir.resolve("missing.jar")));
    }

    @Test
    void currentClasspathFileReusesItsRecordedHashUntilMetadataChanges() throws IOException {
        Path jar = tempDir.resolve("library.jar");
        Files.writeString(jar, "content");
        IncrementalCompileState.ClasspathEntry recorded =
                IncrementalCompileInputHasher.classpathEntry(jar);
        IncrementalCompileState.ClasspathEntry cached = new IncrementalCompileState.ClasspathEntry(
                recorded.path(),
                recorded.size(),
                recorded.lastModifiedNanos(),
                "cached-hash");

        assertTrue(IncrementalCompileInputHasher.classpathEntryCurrent(cached));
        assertEquals(cached, IncrementalCompileInputHasher.classpathEntry(jar, cached));

        Files.writeString(jar, "changed content");

        assertFalse(IncrementalCompileInputHasher.classpathEntryCurrent(cached));
        assertNotEquals(cached, IncrementalCompileInputHasher.classpathEntry(jar, cached));
    }

    @Test
    void classpathDirectoryStillUsesItsContentHash() throws IOException {
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("Example.class"), "old");
        IncrementalCompileState.ClasspathEntry recorded =
                IncrementalCompileInputHasher.classpathEntry(classes);

        assertTrue(IncrementalCompileInputHasher.classpathEntryCurrent(recorded));
        Files.writeString(classes.resolve("Example.class"), "new");

        assertFalse(IncrementalCompileInputHasher.classpathEntryCurrent(recorded));
    }

    @Test
    void relativePathUsesProjectRelativePathWhenInsideProject() {
        Path projectRoot = tempDir.toAbsolutePath().normalize();

        assertEquals(
                "src/main/java/App.java",
                IncrementalCompileInputHasher.relative(projectRoot, projectRoot.resolve("src/main/java/App.java")));
    }
}
