package sh.zolt.toolchain.install;

import sh.zolt.error.ActionableException;
import sh.zolt.toolchain.catalog.JavaToolchainArchiveFormat;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

public final class JavaToolchainInstaller {
    private final JavaToolchainDownloader downloader;
    private final JavaToolchainArchiveExtractor extractor;

    public JavaToolchainInstaller() {
        this(new JavaToolchainDownloader(), new JavaToolchainArchiveExtractor());
    }

    public JavaToolchainInstaller(JavaToolchainDownloader downloader) {
        this(downloader, new JavaToolchainArchiveExtractor());
    }

    JavaToolchainInstaller(
            JavaToolchainDownloader downloader,
            JavaToolchainArchiveExtractor extractor) {
        this.downloader = downloader;
        this.extractor = extractor;
    }

    public boolean install(
            LockedJavaToolchain locked,
            JavaToolchainArtifact artifact,
            ToolchainStore store) {
        requireLockedArtifact(locked, artifact);
        if (store.installed(locked)) {
            return false;
        }
        Path archive = store.downloadArchive(locked, extension(artifact.format()));
        Path staging = store.installRoot(locked).resolveSibling("jdk.tmp");
        Path installRoot = store.installRoot(locked);
        try {
            deleteDirectory(staging);
            Files.createDirectories(staging);
            downloader.download(artifact, archive);
            JavaToolchainChecksum.verifySha256(archive, locked.artifactSha256());
            extractor.extract(archive, artifact.format(), staging, artifact.stripTopLevelDirectory());
            makeExpectedToolsExecutable(locked, staging.resolve(locked.layout().javaHome()));
            if (Files.exists(installRoot)) {
                deleteDirectory(installRoot);
            }
            Files.createDirectories(installRoot.getParent());
            Files.move(staging, installRoot, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new ActionableException(
                    "Could not install Java toolchain at " + installRoot + ".",
                    "Check that the toolchain install root is writable and retry `zolt toolchain sync`.");
        } finally {
            deleteDirectoryIfExists(staging);
        }
        if (!store.installed(locked)) {
            throw new ActionableException(
                    "Downloaded Java toolchain did not contain the expected executables.",
                    "The archive must provide java, javac, jar"
                            + (locked.request().requiresNativeImage() ? ", and native-image." : "."));
        }
        return true;
    }

    private static void requireLockedArtifact(
            LockedJavaToolchain locked,
            JavaToolchainArtifact artifact) {
        String artifactSha256 = artifact.sha256().orElseThrow();
        if (!artifact.uri().toString().equals(locked.artifactUri())
                || !artifactSha256.equalsIgnoreCase(locked.artifactSha256())) {
            throw new ActionableException(
                    "Java toolchain catalog artifact does not match zolt.lock.",
                    "Run `zolt toolchain sync --refresh` to replace stale or unsafe toolchain metadata.");
        }
    }

    private static void makeExpectedToolsExecutable(LockedJavaToolchain locked, Path javaHome) {
        javaHome.resolve(locked.layout().java()).toFile().setExecutable(true, false);
        javaHome.resolve(locked.layout().javac()).toFile().setExecutable(true, false);
        javaHome.resolve(locked.layout().jar()).toFile().setExecutable(true, false);
        if (!locked.layout().nativeImage().isBlank()) {
            javaHome.resolve(locked.layout().nativeImage()).toFile().setExecutable(true, false);
        }
    }

    private static String extension(JavaToolchainArchiveFormat format) {
        return switch (format) {
            case TAR_GZ -> "tar.gz";
            case ZIP -> "zip";
        };
    }

    private static void deleteDirectoryIfExists(Path directory) {
        try {
            deleteDirectory(directory);
        } catch (IOException ignored) {
            // Best-effort cleanup only; the user-facing install error was already raised.
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
