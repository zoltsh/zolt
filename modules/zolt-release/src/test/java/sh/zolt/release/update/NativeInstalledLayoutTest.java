package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeInstalledLayoutTest {
    @TempDir
    private Path tempDir;

    @Test
    void detectsInstallerManagedLayoutFromCurrentBinLink() throws IOException {
        Path installRoot = install("0.1.0");
        Path binLink = installRoot.resolve("bin/zolt");

        NativeInstalledLayout layout = NativeInstalledLayout.detect(installRoot, binLink);

        assertEquals(installRoot.toRealPath().resolve("bin/zolt"), layout.binLink());
        assertEquals(Path.of("../versions", "0.1.0", "bin", "zolt"), layout.linkTarget());
        assertEquals(installRoot.toRealPath().resolve("versions"), layout.versionsDirectory());
        assertEquals("0.1.0", layout.version());
    }

    @Test
    void detectsInstallerManagedLayoutFromResolvedCurrentExecutable() throws IOException {
        Path installRoot = install("0.1.0");
        Path currentExecutable = installRoot.resolve("versions/0.1.0/bin/zolt");

        NativeInstalledLayout layout = NativeInstalledLayout.detect(installRoot, currentExecutable);

        assertEquals("0.1.0", layout.version());
        assertEquals(installRoot.toRealPath().resolve("bin/zolt"), layout.binLink());
    }

    @Test
    void rejectsNonSymlinkBinEntry() throws IOException {
        Path installRoot = tempDir.resolve("home/.zolt");
        Path bin = installRoot.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("zolt"), "not a symlink");

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> NativeInstalledLayout.detect(installRoot, bin.resolve("zolt")));

        assertTrue(exception.getMessage().contains("Installer-managed native Zolt layouts"));
        assertTrue(exception.getMessage().contains("must use a symlink"));
    }

    @Test
    void rejectsExecutableOutsideTheManagedInstallation() throws IOException {
        Path installRoot = install("0.1.0");
        Path otherExecutable = tempDir.resolve("dev/zolt");
        Files.createDirectories(otherExecutable.getParent());
        Files.writeString(otherExecutable, "dev");

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> NativeInstalledLayout.detect(installRoot, otherExecutable));

        assertTrue(exception.getMessage().contains("completed managed executable"));
        assertTrue(exception.getMessage().contains(installRoot.resolve("versions").toString()));
    }

    @Test
    void rejectsBinSymlinkOutsideVersionsDirectory() throws IOException {
        Path installRoot = tempDir.resolve("home/.zolt");
        Path externalBinary = installRoot.resolve("external/bin/zolt");
        writeExecutable(externalBinary);
        Path binLink = installRoot.resolve("bin/zolt");
        Files.createDirectories(binLink.getParent());
        createSymlink(binLink, Path.of("../external/bin/zolt"));

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> NativeInstalledLayout.detect(installRoot, binLink));

        assertTrue(exception.getMessage().contains("versioned installs"));
        assertTrue(exception.getMessage().contains(installRoot.resolve("versions").toString()));
    }

    @Test
    void rejectsVersionSymlinkWithoutBinSegment() throws IOException {
        Path installRoot = tempDir.resolve("home/.zolt");
        Path malformedBinary = installRoot.resolve("versions/0.1.0/zolt");
        writeExecutable(malformedBinary);
        Path binLink = installRoot.resolve("bin/zolt");
        Files.createDirectories(binLink.getParent());
        createSymlink(binLink, Path.of("../versions/0.1.0/zolt"));

        NativeUpdateException exception = assertThrows(
                NativeUpdateException.class,
                () -> NativeInstalledLayout.detect(installRoot, binLink));

        assertTrue(exception.getMessage().contains("versioned installs"));
    }

    private Path install(String version) throws IOException {
        Path installRoot = tempDir.resolve("home/.zolt");
        Path executable = installRoot.resolve("versions").resolve(version).resolve("bin/zolt");
        writeExecutable(executable);
        Path binLink = installRoot.resolve("bin/zolt");
        Files.createDirectories(binLink.getParent());
        createSymlink(binLink, Path.of("../versions", version, "bin", "zolt"));
        return installRoot;
    }

    private static void writeExecutable(Path executable) throws IOException {
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "native");
        executable.toFile().setExecutable(true);
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
