package sh.zolt.release.update;

import sh.zolt.release.channel.ReleaseChannelArtifact;
import sh.zolt.release.channel.ReleaseIndexManifest;
import sh.zolt.release.channel.ReleaseIndexVersion;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class NativeVersionInstallService {
    private final NativeReleaseIndexService releaseIndexService;
    private final NativeUpdateTransport transport;

    public NativeVersionInstallService() {
        this(new NativeReleaseIndexService(), NativeUpdateTransport.standard());
    }

    NativeVersionInstallService(NativeReleaseIndexService releaseIndexService, NativeUpdateTransport transport) {
        this.releaseIndexService = releaseIndexService;
        this.transport = transport;
    }

    public NativeVersionInstallResult install(NativeVersionInstallRequest request) {
        try (NativeInstallLease lease = NativeInstallLease.acquireManaged(request.installRoot(), request.currentExecutable())) {
            NativeInstalledLayout installed = NativeInstalledLayout.detect(lease.root(), lease.root().resolve("bin/zolt"));
            String version = safeVersion(request.version());
            Path versionsRoot = NativeInstallPaths.ownedDirectory(installed.versionsDirectory(), "native versions directory");
            ReleaseIndexManifest index = releaseIndexService.read(new NativeReleaseListRequest(request.releaseIndexUri()));
            ReleaseIndexVersion release = release(index, version);
            ReleaseChannelArtifact artifact = release.artifactFor(request.target());

            Path installDirectory = NativeInstallPaths.resolveUnderRoot(versionsRoot, version, "native install directory");
            Path installedBinary = installDirectory.resolve("bin").resolve(artifact.binaryName()).normalize();
            NativeInstallPaths.assertUnderRoot(installDirectory, installedBinary, "native installed binary");
            if (NativeInstallAttempt.reuseCompleted(installDirectory, artifact.binaryName(), version)) {
                return new NativeVersionInstallResult(index.channel(), version, request.target(), false, installedBinary);
            }

            try (NativeInstallAttempt attempt = new NativeInstallAttempt(versionsRoot)) {
                Path workRoot = attempt.root();
                Path archive = NativeInstallPaths.resolveUnderRoot(workRoot, artifact.archive(), "native install archive");
                transport.download(URI.create(artifact.archiveUrl()), archive);
                verifyChecksum(archive, expectedChecksum(artifact, workRoot));

                Path extractDirectory = NativeInstallPaths.resolveUnderRoot(workRoot, "extract", "native install extraction directory");
                NativeInstallPaths.deleteIfExists(workRoot, extractDirectory, "native install extraction directory");
                Files.createDirectories(extractDirectory);
                ReleaseArchiveSupport.unpack(archive, extractDirectory, artifact);
                Path candidateRoot = singleDirectory(extractDirectory);
                NativeInstallPaths.assertUnderRoot(extractDirectory, candidateRoot, "native install extraction candidate");
                Path candidateBinary = candidateRoot.resolve("bin").resolve(artifact.binaryName()).normalize();
                NativeInstallPaths.assertUnderRoot(candidateRoot, candidateBinary, "native install candidate binary");
                if (!Files.isExecutable(candidateBinary)) {
                    throw new NativeUpdateException("Downloaded native Zolt archive does not contain executable bin/" + artifact.binaryName() + ".");
                }

                // Reject unusable candidates before touching any installed version or rollback target.
                NativeUpdateVerifier.smokeCandidate(candidateBinary, version);

                NativeInstallAttempt.publish(candidateRoot, installDirectory);
            }
            Path finalBinary = installDirectory.resolve("bin").resolve(artifact.binaryName());
            NativeInstallPaths.assertUnderRoot(installDirectory, finalBinary, "native installed binary");
            return new NativeVersionInstallResult(index.channel(), version, request.target(), true, finalBinary);
        } catch (IOException exception) {
            throw new NativeUpdateException("Could not install native Zolt version: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NativeUpdateException("Could not install native Zolt version: smoke verification was interrupted.", exception);
        }
    }

    private static ReleaseIndexVersion release(ReleaseIndexManifest index, String version) {
        return index.versions().stream()
                .filter(candidate -> candidate.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new NativeUpdateException(
                        "Release index `" + index.channel() + "` does not include native Zolt version `" + version + "`."));
    }

    private String expectedChecksum(ReleaseChannelArtifact artifact, Path workDirectory) throws IOException {
        Optional<String> inline = artifact.sha256();
        if (inline.isPresent()) {
            return inline.orElseThrow();
        }
        String checksumUrl = artifact.checksumUrl()
                .orElseThrow(() -> new NativeUpdateException("Release index artifact `" + artifact.target().id() + "` must include checksumUrl or sha256."));
        Path checksum = NativeInstallPaths.resolveUnderRoot(workDirectory, artifact.archive() + ".sha256", "native install checksum");
        transport.download(URI.create(checksumUrl), checksum);
        return Files.readString(checksum).split("\\s+")[0];
    }

    private static void verifyChecksum(Path archive, String expected) throws IOException {
        String actual = sha256(archive);
        if (!actual.equals(expected)) {
            throw new NativeUpdateException("Checksum mismatch for native Zolt archive. Expected " + expected + " but found " + actual + ".");
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read = input.read(buffer);
                while (read >= 0) {
                    digest.update(buffer, 0, read);
                    read = input.read(buffer);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new NativeUpdateException("Could not verify native Zolt archive because SHA-256 is unavailable.", exception);
        }
    }

    private static Path singleDirectory(Path directory) throws IOException {
        List<Path> directories;
        try (var stream = Files.list(directory)) {
            directories = stream.filter(Files::isDirectory).toList();
        }
        if (directories.size() != 1) {
            throw new NativeUpdateException("Native Zolt archive must unpack to exactly one top-level directory.");
        }
        return directories.get(0);
    }

    private static String safeVersion(String version) {
        String normalized = Objects.requireNonNull(version, "version").strip();
        if (normalized.isEmpty()
                || normalized.contains("/")
                || normalized.contains("\\")
                || normalized.contains("..")
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new NativeUpdateException("Native Zolt version must be one version path segment.");
        }
        return normalized;
    }
}
