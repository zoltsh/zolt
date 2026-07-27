package sh.zolt.build.fingerprint;

import sh.zolt.build.BuildException;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.build.lockfile.VerifiedArtifactHashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

final class BuildFingerprintFileHasher {
    private static final List<String> LOCAL_FINGERPRINT_FILES = List.of(
            ".zolt-build-main.fingerprint",
            ".zolt-build-test.fingerprint",
            ".zolt-build-main.fingerprint.state",
            ".zolt-build-test.fingerprint.state");

    /**
     * All machine-local metadata a compile output may hold. Excluded when hashing a dependency output
     * directory for a build-cache key so the key is stable whether the dependency was compiled (has
     * incremental state) or restored (has none), and portable across machines (no absolute paths).
     */
    private static final List<String> LOCAL_METADATA_FILES = List.of(
            ".zolt-build-main.fingerprint",
            ".zolt-build-test.fingerprint",
            ".zolt-build-main.fingerprint.state",
            ".zolt-build-test.fingerprint.state",
            ".zolt-incremental-main.state",
            ".zolt-incremental-test.state");

    private final Function<Path, Optional<String>> verifiedArtifactHash;

    BuildFingerprintFileHasher() {
        this(VerifiedArtifactHashes::currentHash);
    }

    BuildFingerprintFileHasher(Function<Path, Optional<String>> verifiedArtifactHash) {
        this.verifiedArtifactHash = verifiedArtifactHash;
    }

    String hashText(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    String classpathHash(
            Path path,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            Optional<String> abiHash = zoltOutputAbiHash(normalized);
            if (abiHash.isPresent()) {
                return "abi:" + abiHash.orElseThrow();
            }
        }
        return fileHash(normalized, cachedState, collectedState);
    }

    /**
     * Content hash of a classpath entry for build-cache KEY derivation. A directory is hashed by its
     * compiled bytes with all machine-local metadata excluded (never the incremental-state abi hash), so
     * a restored dependency keys identically to a freshly compiled one. Files hash by content as usual.
     */
    String classpathKeyHash(
            Path path,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return "content:" + directoryHash(normalized, cachedState, collectedState, LOCAL_METADATA_FILES);
        }
        return fileHash(normalized, cachedState, collectedState);
    }

    String fileHash(
            Path path,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return directoryHash(normalized, cachedState, collectedState);
        }
        if (!Files.isRegularFile(normalized)) {
            return "missing";
        }
        if (cachedState != null) {
            Optional<BuildFingerprintCachedFileHash> cached = cachedState.fileIfCurrent(normalized);
            if (cached.isPresent()) {
                if (collectedState != null) {
                    collectedState.put(normalized, cached.orElseThrow());
                }
                return cached.orElseThrow().hash();
            }
        }
        Optional<String> verified = verifiedArtifactHash.apply(normalized);
        if (verified.isPresent()) {
            recordCollectedState(normalized, verified.orElseThrow(), collectedState);
            return verified.orElseThrow();
        }
        try {
            String hash = sha256(Files.readAllBytes(normalized));
            recordCollectedState(normalized, hash, collectedState);
            return hash;
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not fingerprint file "
                            + normalized
                            + ". Check that it is readable.",
                    exception);
        }
    }

    private static void recordCollectedState(
            Path path,
            String hash,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        if (collectedState != null) {
            try {
                collectedState.put(path, BuildFingerprintCachedFileHash.read(path, hash));
            } catch (IOException exception) {
                throw new BuildException(
                        "Could not fingerprint file "
                                + path
                                + ". Check that it is readable.",
                        exception);
            }
        }
    }

    String relative(Path projectRoot, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(projectRoot)) {
            return projectRoot.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    private Optional<String> zoltOutputAbiHash(Path directory) {
        Optional<IncrementalCompileState> state = new IncrementalCompileStateCodec()
                .read(IncrementalCompileState.mainStatePath(directory));
        if (state.isEmpty() || !state.orElseThrow().outputDirectory().equals(directory)) {
            return Optional.empty();
        }
        StringBuilder content = new StringBuilder();
        for (IncrementalCompileState.ClassRecord classRecord : state.orElseThrow().classes()) {
            content.append(classRecord.binaryName())
                    .append('|')
                    .append(classRecord.abiHash())
                    .append('|')
                    .append(classRecord.packagePrivateAbiHash())
                    .append('\n');
        }
        return Optional.of(hashText(content.toString()));
    }

    private String directoryHash(
            Path directory,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState) {
        return directoryHash(directory, cachedState, collectedState, LOCAL_FINGERPRINT_FILES);
    }

    private String directoryHash(
            Path directory,
            BuildFingerprintState cachedState,
            Map<Path, BuildFingerprintCachedFileHash> collectedState,
            List<String> excludedFileNames) {
        try (Stream<Path> paths = Files.walk(directory)) {
            StringBuilder content = new StringBuilder();
            paths.filter(Files::isRegularFile)
                    .map(Path::normalize)
                    .filter(path -> !excludedFileNames.contains(path.getFileName().toString()))
                    .sorted()
                    .forEach(path -> content
                            .append(directory.relativize(path).toString().replace('\\', '/'))
                            .append('|')
                            .append(fileHash(path, cachedState, collectedState))
                            .append('\n'));
            return hashText(content.toString());
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not fingerprint directory "
                            + directory
                            + ". Check that it is readable.",
                    exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException("Could not compute build fingerprint because SHA-256 is unavailable.", exception);
        }
    }
}
