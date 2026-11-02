package sh.zolt.release.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** All downloads and extraction belong to one attempt on the destination filesystem. */
final class NativeInstallAttempt implements AutoCloseable {
    private final Path root;

    NativeInstallAttempt(Path versionsRoot) throws IOException {
        root = Files.createTempDirectory(versionsRoot, ".install-attempt-");
    }

    Path root() {
        return root;
    }

    static boolean reuseCompleted(Path installDirectory, String binaryName, String version)
            throws IOException, InterruptedException {
        if (!Files.exists(installDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Path binary = installDirectory.resolve("bin").resolve(binaryName);
        if (!Files.isDirectory(installDirectory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isExecutable(binary)
                || !binary.toRealPath().startsWith(installDirectory.toRealPath())) {
            throw new NativeUpdateException("Existing native version is incomplete or unsafe at " + installDirectory
                    + ". Next: select a usable version before removing this incomplete installation.");
        }
        NativeUpdateVerifier.smokeCandidate(binary, version);
        return true;
    }

    static void publish(Path candidateRoot, Path installDirectory) throws IOException {
        if (Files.exists(installDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new NativeUpdateException("Refusing to replace an existing native version at " + installDirectory + ".");
        }
        Files.move(candidateRoot, installDirectory, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void close() throws IOException {
        NativeInstallPaths.deleteIfExists(root.getParent(), root, "owned native installation attempt");
    }
}
