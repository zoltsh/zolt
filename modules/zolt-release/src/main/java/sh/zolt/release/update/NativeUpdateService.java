package sh.zolt.release.update;

import sh.zolt.release.ReleaseTarget;
import sh.zolt.release.channel.ReleaseChannelArtifact;
import sh.zolt.release.channel.ReleaseChannelManifest;
import sh.zolt.release.channel.ReleaseChannelManifestValidator;
import sh.zolt.release.signing.ReleaseSignatureException;
import sh.zolt.release.signing.ReleaseSignatureVerifier;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

public final class NativeUpdateService {
    private static final int MAX_CHANNEL_MANIFEST_BYTES = 1_048_576;
    private static final int MAX_CHANNEL_SIGNATURE_BYTES = 8_192;

    private final ReleaseChannelManifestValidator manifestValidator;
    private final ReleaseSignatureVerifier signatureVerifier;
    private final NativeUpdateTransport transport;

    public NativeUpdateService() {
        this(
                new ReleaseChannelManifestValidator(),
                ReleaseSignatureVerifier.bundled(),
                NativeUpdateTransport.standard());
    }

    NativeUpdateService(
            ReleaseChannelManifestValidator manifestValidator,
            ReleaseSignatureVerifier signatureVerifier,
            NativeUpdateTransport transport) {
        this.manifestValidator = manifestValidator;
        this.signatureVerifier = signatureVerifier;
        this.transport = transport;
    }

    public NativeUpdateResult update(NativeUpdateRequest request) {
        try {
            NativeInstalledLayout installed = NativeInstalledLayout.detect(request.installRoot(), request.currentExecutable());
            ReleaseChannelUriPolicy.validate(request.channelUri(), true);
            byte[] manifestBytes = transport.downloadBytes(
                    request.channelUri(),
                    MAX_CHANNEL_MANIFEST_BYTES,
                    "release channel manifest");
            verifyManifestSignature(request.channelUri(), manifestBytes);
            ReleaseChannelManifest manifest = validateManifest(
                    request.channelUri(),
                    new String(manifestBytes, StandardCharsets.UTF_8));
            ReleaseChannelUriPolicy.requireChannelDocument(
                    request.channelUri(), "channels", manifest.channel());
            ReleaseChannelArtifact artifact = manifest.artifactFor(request.target());
            if (installed.version().equals(manifest.version())) {
                return new NativeUpdateResult(
                        manifest.channel(),
                        request.target(),
                        installed.version(),
                        manifest.version(),
                        false,
                        installed.binLink());
            }

            Path workDirectory = request.workDirectory() == null
                    ? Files.createTempDirectory("zolt-update-")
                    : request.workDirectory().toAbsolutePath().normalize();
            Path workRoot = NativeInstallPaths.ownedDirectory(workDirectory, "native update work directory");
            Path versionsRoot = NativeInstallPaths.ownedDirectory(installed.versionsDirectory(), "native versions directory");
            Path binRoot = NativeInstallPaths.ownedDirectory(installed.binLink().getParent(), "native bin directory");
            Path archive = NativeInstallPaths.resolveUnderRoot(workRoot, artifact.archive(), "native update archive");
            transport.download(URI.create(artifact.archiveUrl()), archive);
            verifyChecksum(archive, expectedChecksum(artifact, workRoot));

            Path extractDirectory = NativeInstallPaths.resolveUnderRoot(workRoot, "extract", "native update extraction directory");
            NativeInstallPaths.deleteIfExists(workRoot, extractDirectory, "native update extraction directory");
            Files.createDirectories(extractDirectory);
            ReleaseArchiveSupport.unpack(archive, extractDirectory, artifact);
            Path candidateRoot = singleDirectory(extractDirectory);
            NativeInstallPaths.assertUnderRoot(extractDirectory, candidateRoot, "native update extraction candidate");
            Path candidateBinary = candidateRoot.resolve("bin").resolve(artifact.binaryName()).normalize();
            NativeInstallPaths.assertUnderRoot(candidateRoot, candidateBinary, "native update candidate binary");
            if (!Files.isExecutable(candidateBinary)) {
                throw new NativeUpdateException("Downloaded native Zolt archive does not contain executable bin/" + artifact.binaryName() + ".");
            }

            Path installDirectory = NativeInstallPaths.resolveUnderRoot(versionsRoot, manifest.version(), "native install directory");
            Path tempInstallDirectory = NativeInstallPaths.resolveUnderRoot(versionsRoot, manifest.version() + ".tmp", "native temporary install directory");
            NativeInstallPaths.deleteIfExists(versionsRoot, tempInstallDirectory, "native temporary install directory");
            Files.move(candidateRoot, tempInstallDirectory);
            NativeInstallPaths.deleteIfExists(versionsRoot, installDirectory, "native install directory");
            Files.move(tempInstallDirectory, installDirectory);
            Path installedBinary = installDirectory.resolve("bin").resolve(artifact.binaryName());
            NativeInstallPaths.assertUnderRoot(installDirectory, installedBinary, "native installed binary");
            NativeUpdateVerifier.smokeCandidate(installedBinary, manifest.version());
            Path nextTarget = Path.of("../versions", manifest.version(), "bin", artifact.binaryName());
            NativeInstallPaths.assertUnderRoot(versionsRoot, binRoot.resolve(nextTarget), "native update symlink target");
            // binRoot is realpath-resolved (ownedDirectory), so derive the bin symlink in the same canonical
            // space; otherwise the containment guard's non-following comparison mismatches on hosts where the
            // bin directory's ancestor is a symlink (e.g. macOS /var -> /private/var).
            Path canonicalBinLink = binRoot.resolve(installed.binLink().getFileName());
            NativeVersionService.writePreviousVersion(installed.installRoot(), installed.version());
            NativeInstallPaths.switchCurrent(binRoot, canonicalBinLink, installed.linkTarget(), nextTarget);

            return new NativeUpdateResult(
                    manifest.channel(),
                    request.target(),
                    installed.version(),
                    manifest.version(),
                    true,
                    installed.binLink());
        } catch (IOException exception) {
            throw new NativeUpdateException("Could not update native Zolt: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new NativeUpdateException("Could not update native Zolt: update smoke was interrupted.", exception);
        }
    }

    private ReleaseChannelManifest validateManifest(URI channelUri, String json) {
        if (ReleaseChannelUriPolicy.isLocalFile(channelUri)) {
            return manifestValidator.validateLocalManifest(json);
        }
        return manifestValidator.validate(json);
    }

    private void verifyManifestSignature(URI channelUri, byte[] manifestBytes) throws IOException {
        if (ReleaseChannelUriPolicy.isLocalFile(channelUri)) {
            return;
        }
        URI signatureUri = ReleaseChannelSignatureUris.sidecar(channelUri);
        String sidecarText;
        try {
            sidecarText = new String(
                    transport.downloadBytes(
                            signatureUri,
                            MAX_CHANNEL_SIGNATURE_BYTES,
                            "release channel signature"),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new NativeUpdateException(
                    "Release channel signature is required but could not be downloaded from " + signatureUri + ".",
                    exception);
        }
        try {
            signatureVerifier.verify(manifestBytes, sidecarText);
        } catch (ReleaseSignatureException exception) {
            throw new NativeUpdateException(
                    "Release channel signature verification failed: " + exception.getMessage(),
                    exception);
        }
    }

    private String expectedChecksum(ReleaseChannelArtifact artifact, Path workDirectory) throws IOException {
        Optional<String> inline = artifact.sha256();
        if (inline.isPresent()) {
            return inline.orElseThrow();
        }
        String checksumUrl = artifact.checksumUrl()
                .orElseThrow(() -> new NativeUpdateException("Release channel artifact `" + artifact.target().id() + "` must include checksumUrl or sha256."));
        Path checksum = NativeInstallPaths.resolveUnderRoot(workDirectory, artifact.archive() + ".sha256", "native update checksum");
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

}
