package sh.zolt.toolchain.install;

import sh.zolt.error.ActionableException;
import sh.zolt.toolchain.catalog.JavaToolchainArchiveFormat;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.store.ToolchainStore;
import sh.zolt.toolchain.store.ToolchainStorePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JavaToolchainInstaller {
    private final JavaToolchainDownloader downloader;
    private final JavaToolchainArchiveExtractor extractor;
    private final Move move;

    public JavaToolchainInstaller() {
        this(new JavaToolchainDownloader(), new JavaToolchainArchiveExtractor());
    }

    public JavaToolchainInstaller(JavaToolchainDownloader downloader) {
        this(downloader, new JavaToolchainArchiveExtractor());
    }

    JavaToolchainInstaller(JavaToolchainDownloader downloader, JavaToolchainArchiveExtractor extractor) {
        this(downloader, extractor, (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE));
    }

    JavaToolchainInstaller(JavaToolchainDownloader downloader, JavaToolchainArchiveExtractor extractor, Move move) {
        this.downloader = downloader;
        this.extractor = extractor;
        this.move = move;
    }

    public boolean install(LockedJavaToolchain locked, JavaToolchainArtifact artifact, ToolchainStore store) {
        requireLockedArtifact(locked, artifact);
        try (JavaToolchainInstallLock ignored = JavaToolchainInstallLock.acquire(store, locked)) {
            // Separate projects can share the store: installed state is authoritative only under this lease.
            if (store.installed(locked)) {
                return false;
            }
            return installLocked(locked, artifact, store);
        }
    }

    private boolean installLocked(LockedJavaToolchain locked, JavaToolchainArtifact artifact, ToolchainStore store) {
        Path installRoot = store.installRoot(locked);
        Path attempt = null;
        boolean published = false;
        try {
            Path parent = store.checked(installRoot.getParent());
            attempt = store.checked(Files.createTempDirectory(parent, ".jdk-attempt-"));
            Path archive = store.checked(attempt.resolve("archive." + extension(artifact.format())));
            Path staging = store.checked(attempt.resolve("jdk"));
            Files.createDirectory(staging);
            downloader.download(artifact, store.checked(archive));
            JavaToolchainChecksum.verifySha256(store.checked(archive), locked.artifactSha256());
            extractor.extract(store.checked(archive), artifact.format(), store.checked(staging), artifact.stripTopLevelDirectory());
            validateCandidate(locked, store.checked(staging));
            publish(store, staging, installRoot, attempt);
            published = true;
            return true;
        } catch (IOException exception) {
            throw new ActionableException("Could not install Java toolchain at " + installRoot + ".",
                    "Check that the toolchain install root is writable and retry `zolt toolchain sync`.");
        } finally {
            if (attempt != null && (published || !Files.exists(attempt.resolve("previous"), LinkOption.NOFOLLOW_LINKS))) {
                deleteAttemptIfExists(store, attempt);
            }
        }
    }

    private void publish(ToolchainStore store, Path staging, Path destination, Path attempt) throws IOException {
        Path previous = store.checked(attempt.resolve("previous"));
        boolean replaced = Files.exists(store.checked(destination), LinkOption.NOFOLLOW_LINKS);
        if (replaced) {
            move.move(store.checked(destination), previous);
        }
        try {
            move.move(store.checked(staging), store.checked(destination));
        } catch (IOException | RuntimeException exception) {
            if (replaced && !Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    move.move(store.checked(previous), store.checked(destination));
                } catch (IOException | RuntimeException restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                    ActionableException recovery = new ActionableException(
                            "Could not publish or restore the Java toolchain; the previous installation remains at " + previous + ".",
                            "Preserve this recovery directory and restore it to " + destination + " before retrying.");
                    recovery.addSuppressed(exception);
                    throw recovery;
                }
            }
            throw exception;
        }
    }

    private static void requireLockedArtifact(LockedJavaToolchain locked, JavaToolchainArtifact artifact) {
        String artifactSha256 = artifact.sha256().orElseThrow();
        if (!artifact.uri().toString().equals(locked.artifactUri())
                || !artifactSha256.equalsIgnoreCase(locked.artifactSha256())) {
            throw new ActionableException("Java toolchain catalog artifact does not match zolt.lock.",
                    "Run `zolt toolchain sync --refresh` to replace stale or unsafe toolchain metadata.");
        }
    }

    private static void validateCandidate(LockedJavaToolchain locked, Path staging) {
        List<String> tools = new ArrayList<>(List.of(locked.layout().java(), locked.layout().javac(), locked.layout().jar()));
        if (!locked.layout().nativeImage().isBlank()) {
            tools.add(locked.layout().nativeImage());
        }
        Path javaHome = ToolchainStorePaths.within(staging, staging.resolve(locked.layout().javaHome()), true);
        for (String relative : tools) {
            Path executable = ToolchainStorePaths.within(staging, javaHome.resolve(relative), false);
            if (Files.isRegularFile(executable)) {
                executable.toFile().setExecutable(true, false);
            }
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                throw new ActionableException("Downloaded Java toolchain did not contain the expected executable " + relative + ".",
                        "Use an archive providing java, javac, jar, and any requested native-image tool.");
            }
        }
        if (locked.request().requiresNativeImage() && locked.layout().nativeImage().isBlank()) {
            throw new ActionableException("Downloaded Java toolchain has no native-image executable layout.",
                    "Refresh the Java toolchain lock metadata.");
        }
    }

    @FunctionalInterface
    interface Move {
        void move(Path source, Path target) throws IOException;
    }

    private static String extension(JavaToolchainArchiveFormat format) {
        return switch (format) {
            case TAR_GZ -> "tar.gz";
            case ZIP -> "zip";
        };
    }

    private static void deleteAttemptIfExists(ToolchainStore store, Path attempt) {
        try {
            Path root = store.checked(attempt);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    // Unlink symlinks themselves without following their targets during cleanup.
                    if (Files.isSymbolicLink(path)) {
                        ToolchainStorePaths.within(root, path.getParent(), true);
                    } else {
                        ToolchainStorePaths.within(root, path, true);
                    }
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Attempt-owned leftovers are harmless; never clean another installation or lease.
        }
    }
}
