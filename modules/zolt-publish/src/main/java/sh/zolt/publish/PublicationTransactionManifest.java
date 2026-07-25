package sh.zolt.publish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Durable exact-byte transaction identity for ordinary plain-repository publishing. It is written
 * after all files are staged and before the first request, and retained only while an upload is
 * incomplete. A retry can therefore reuse a wall-clock detached signature instead of generating
 * different bytes for an immutable path.
 */
record PublicationTransactionManifest(
        String targetIdentity,
        String signingIdentity,
        Map<String, String> stagedHashes) {
    private static final String SCHEMA = "zolt.single-publish-resume.v1";

    PublicationTransactionManifest {
        stagedHashes = Map.copyOf(stagedHashes);
    }

    static PublicationTransactionManifest of(
            String targetIdentity,
            String signingIdentity,
            List<StagedPublicationFile> staged) {
        return new PublicationTransactionManifest(
                targetIdentity, signingIdentity, hashes(staged));
    }

    /**
     * Namespaces durable state by release destination and full project coordinate. An interrupted
     * release can therefore still resume, while a version bump starts an independent transaction.
     */
    static Path transactionPath(
            Path stagingRoot,
            String targetIdentity,
            String projectCoordinate) {
        String identity = targetIdentity + "\n" + projectCoordinate;
        return stagingRoot
                .resolve("transactions")
                .resolve(sha256(identity))
                .resolve("manifest");
    }

    static Path transactionFilesPath(
            Path stagingRoot,
            String targetIdentity,
            String projectCoordinate) {
        return transactionPath(stagingRoot, targetIdentity, projectCoordinate)
                .getParent()
                .resolve("files");
    }

    static Optional<PublicationTransactionManifest> read(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) {
            throw invalid(file);
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new PublishException(
                    "Could not read the interrupted publish transaction at " + file + ".", exception);
        }
        String schema = value(lines, "schema");
        String target = decode(value(lines, "target"), file);
        String signing = decode(value(lines, "signing"), file);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (String line : lines) {
            if (!line.startsWith("file=")) {
                continue;
            }
            String[] parts = line.substring("file=".length()).split("\\|", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw invalid(file);
            }
            hashes.put(decode(parts[0], file), parts[1]);
        }
        if (!SCHEMA.equals(schema) || target.isBlank() || signing.isBlank() || hashes.isEmpty()) {
            throw invalid(file);
        }
        return Optional.of(new PublicationTransactionManifest(target, signing, hashes));
    }

    void requireIdentity(String currentTarget, String currentSigning) {
        if (!targetIdentity.equals(currentTarget)) {
            throw new PublishException(
                    "Cannot resume the interrupted publish because its repository target changed (recorded `"
                            + targetIdentity
                            + "`, now `"
                            + currentTarget
                            + "`). Restore the original target or start a new release version.");
        }
        if (!signingIdentity.equals(currentSigning)) {
            throw new PublishException(
                    "Cannot resume the interrupted publish because its signing identity changed (recorded `"
                            + signingIdentity
                            + "`, now `"
                            + currentSigning
                            + "`). Restore the original signing configuration.");
        }
    }

    void requirePlan(List<StagedPublicationFile> staged) {
        if (!stagedHashes.equals(hashes(staged))) {
            throw new PublishException(
                    "Cannot resume because the publication path set or exact staged bytes changed. "
                            + "Restore the interrupted publish inputs or start a new release version.");
        }
    }

    PublicationResume resume() {
        return new PublicationResume(stagedHashes);
    }

    void write(Path file) {
        StringBuilder content = new StringBuilder();
        content.append("schema=").append(SCHEMA).append('\n');
        content.append("target=").append(encode(targetIdentity)).append('\n');
        content.append("signing=").append(encode(signingIdentity)).append('\n');
        for (Map.Entry<String, String> entry : new TreeMap<>(stagedHashes).entrySet()) {
            content.append("file=")
                    .append(encode(entry.getKey()))
                    .append('|')
                    .append(entry.getValue())
                    .append('\n');
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(temporary, content.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new PublishException(
                    "Could not persist the publish transaction before uploading at " + file + ".", exception);
        }
    }

    static void deleteTransaction(Path manifest) {
        Path transaction = manifest.getParent();
        Path completed = transaction.resolveSibling(
                transaction.getFileName() + ".completed-" + UUID.randomUUID());
        try {
            if (!Files.exists(transaction)) {
                return;
            }
            try {
                Files.move(transaction, completed, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(transaction, completed);
            }
            try (Stream<Path> paths = Files.walk(completed)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException exception) {
            throw new PublishException(
                    "The publish completed, but its transaction staging directory could not be removed at "
                            + transaction
                            + ".",
                    exception);
        }
    }

    private static Map<String, String> hashes(List<StagedPublicationFile> staged) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (StagedPublicationFile file : staged) {
            String previous = hashes.put(file.repositoryPath(), file.sha256());
            if (previous != null) {
                throw new PublishException(
                        "Duplicate publication repository path `" + file.repositoryPath() + "`.");
            }
        }
        return Map.copyOf(hashes);
    }

    private static String value(List<String> lines, String key) {
        String prefix = key + "=";
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return "";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new PublishException("SHA-256 is unavailable.", exception);
        }
    }

    private static String decode(String value, Path file) {
        if (value.isBlank()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalid(file);
        }
    }

    private static PublishException invalid(Path file) {
        return new PublishException(
                "The interrupted publish transaction at "
                        + file
                        + " is invalid, so its exact signed bytes cannot be resumed safely.");
    }
}
