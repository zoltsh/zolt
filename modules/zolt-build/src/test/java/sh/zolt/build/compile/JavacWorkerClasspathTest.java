package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JavacWorkerClasspathTest {
    @TempDir Path temp;

    @Test
    void installedLauncherFollowsRelativeLinkAndLinkedAncestorWithSpaces() throws IOException {
        Path root = temp.resolve("native installation with spaces");
        Path executable = file(root.resolve("versions/1.0.0/bin/zolt"));
        Path worker = file(root.resolve("versions/1.0.0/libexec/zolt-javac-worker.jar"));
        file(root.resolve("libexec/zolt-javac-worker.jar")); // A stale root worker must not win.
        Path launcher = root.resolve("bin/zolt");
        Files.createDirectories(launcher.getParent());
        Files.createSymbolicLink(launcher, Path.of("../versions/1.0.0/bin/zolt"));
        Path alias = Files.createSymbolicLink(temp.resolve("linked ancestor with spaces"), root);

        assertEquals(Optional.of(worker.toRealPath()), JavacWorkerClasspath.bundledWorker(executable));
        assertEquals(Optional.of(worker.toRealPath()), JavacWorkerClasspath.bundledWorker(alias.resolve("bin/zolt")));
    }

    @Test
    void launcherRetargetDiscoversNewVersionWorker() throws IOException {
        Path first = file(temp.resolve("versions/1.0.0/bin/zolt"));
        Path firstWorker = file(temp.resolve("versions/1.0.0/libexec/zolt-javac-worker.jar"));
        Path second = file(temp.resolve("versions/1.0.1/bin/zolt"));
        Path secondWorker = file(temp.resolve("versions/1.0.1/libexec/zolt-javac-worker.jar"));
        Path launcher = Files.createSymbolicLink(temp.resolve("zolt"), first);
        assertEquals(Optional.of(firstWorker.toRealPath()), JavacWorkerClasspath.bundledWorker(launcher));
        Files.delete(launcher);
        Files.createSymbolicLink(launcher, second);
        assertEquals(Optional.of(secondWorker.toRealPath()), JavacWorkerClasspath.bundledWorker(launcher));
    }

    @Test
    void standaloneExecutableRetainsAdjacentLibexecDiscovery() throws IOException {
        Path executable = file(temp.resolve("standalone/zolt"));
        Path worker = file(temp.resolve("standalone/libexec/zolt-javac-worker.jar"));
        assertEquals(Optional.of(worker.toRealPath()), JavacWorkerClasspath.bundledWorker(executable));
    }

    @Test
    void unavailableExecutableOrWorkerLeavesExternalCompilerFallbackAvailable() throws IOException {
        Path executable = file(temp.resolve("bin/zolt"));
        assertTrue(JavacWorkerClasspath.bundledWorker(executable).isEmpty());
        file(temp.resolve("libexec/zolt-javac-worker.jar"));
        Files.delete(executable);
        assertTrue(JavacWorkerClasspath.bundledWorker(executable).isEmpty());
        Path dangling = Files.createSymbolicLink(temp.resolve("dangling"), executable);
        assertTrue(JavacWorkerClasspath.bundledWorker(dangling).isEmpty());
        Files.createDirectories(executable);
        assertTrue(JavacWorkerClasspath.bundledWorker(executable).isEmpty());
    }

    @Test
    void explicitWorkerOverrideStillWins() throws IOException {
        Path configured = file(temp.resolve("configured worker.jar"));
        String previous = System.getProperty(JavacWorkerClasspath.PROPERTY);
        try {
            System.setProperty(JavacWorkerClasspath.PROPERTY, configured.toString());
            assertEquals(Optional.of(configured.toAbsolutePath().normalize()), JavacWorkerClasspath.discover());
        } finally {
            if (previous == null) System.clearProperty(JavacWorkerClasspath.PROPERTY);
            else System.setProperty(JavacWorkerClasspath.PROPERTY, previous);
        }
    }

    private Path file(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "fixture");
    }
}
