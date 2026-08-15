package sh.zolt.lockfile;

import java.util.Optional;
import java.util.regex.Pattern;
import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;

/** Capability gate for lock consumers that materialize artifact cache paths. */
public final class ContentAddressedLockCapability {
    public static final int MINIMUM_VERSION = 6;
    private static final String PATH_PREFIX = "blobs/v2/sha256/";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private ContentAddressedLockCapability() {}

    /** Older locks remain readable, but their Maven-layout paths must not be reinterpreted. */
    public static boolean supportsArtifactCachePaths(ZoltLockfile lockfile) {
        return lockfile.version() >= MINIMUM_VERSION;
    }

    /** Refuses legacy cache paths before a locked resolve performs cache or network work. */
    public static void requireArtifactCachePaths(ZoltLockfile lockfile, String migrationCommand) {
        if (!supportsArtifactCachePaths(lockfile) && usesArtifactCachePaths(lockfile)) {
            throw new ActionableException(ActionableError.of(
                    "zolt.lock version "
                            + lockfile.version()
                            + " predates the version "
                            + MINIMUM_VERSION
                            + " content-addressed artifact cache path contract required by this Zolt.",
                    remediation(migrationCommand)));
        }
        if (!supportsArtifactCachePaths(lockfile)) {
            return;
        }
        for (LockPackage lockPackage : lockfile.packages()) {
            requireArtifactPair(lockPackage, "jar", lockPackage.jar(), lockPackage.jarSha256(), migrationCommand);
            requireArtifactPair(lockPackage, "pom", lockPackage.pom(), lockPackage.pomSha256(), migrationCommand);
            requireSecondaryArtifact(lockPackage, migrationCommand);
        }
    }

    /** Refuses every pre-v6 lock before an executable command can resolve, compile, or package. */
    public static void requireExecutableLockfile(ZoltLockfile lockfile, String migrationCommand) {
        requireArtifactCachePaths(lockfile, migrationCommand);
        if (!supportsArtifactCachePaths(lockfile)) {
            throw new ActionableException(ActionableError.of(
                    "zolt.lock version "
                            + lockfile.version()
                            + " predates the version "
                            + MINIMUM_VERSION
                            + " executable lock contract required by this Zolt.",
                    remediation(migrationCommand)));
        }
    }

    private static boolean usesArtifactCachePaths(ZoltLockfile lockfile) {
        return lockfile.packages().stream().anyMatch(lockPackage -> lockPackage.jar().isPresent()
                || lockPackage.pom().isPresent()
                || lockPackage.jarSha256().isPresent()
                || lockPackage.pomSha256().isPresent()
                || lockPackage.artifact().isPresent()
                || lockPackage.artifactType().isPresent()
                || lockPackage.artifactSha256().isPresent());
    }

    private static void requireSecondaryArtifact(LockPackage lockPackage, String migrationCommand) {
        boolean pathPresent = lockPackage.artifact().isPresent();
        boolean typePresent = lockPackage.artifactType().isPresent();
        boolean checksumPresent = lockPackage.artifactSha256().isPresent();
        if (!(pathPresent == typePresent && typePresent == checksumPresent)) {
            throw invalid(
                    lockPackage,
                    "secondary artifact must record `artifact`, `artifactType`, and `artifactSha256` together",
                    migrationCommand);
        }
        if (typePresent && lockPackage.artifactType().orElseThrow().isBlank()) {
            throw invalid(lockPackage, "`artifactType` must not be blank", migrationCommand);
        }
        requireArtifactPair(
                lockPackage,
                "artifact",
                lockPackage.artifact(),
                lockPackage.artifactSha256(),
                migrationCommand);
    }

    private static void requireArtifactPair(
            LockPackage lockPackage,
            String field,
            Optional<String> path,
            Optional<String> checksum,
            String migrationCommand) {
        if (path.isPresent() != checksum.isPresent()) {
            throw invalid(
                    lockPackage,
                    "`" + field + "` and `" + field + "Sha256` must be recorded together",
                    migrationCommand);
        }
        if (path.isEmpty()) {
            return;
        }
        String recordedChecksum = checksum.orElseThrow();
        if (!SHA_256.matcher(recordedChecksum).matches()) {
            throw invalid(
                    lockPackage,
                    "`" + field + "Sha256` must be exactly 64 lowercase hexadecimal characters",
                    migrationCommand);
        }
        String recordedPath = path.orElseThrow();
        if (!recordedPath.startsWith(PATH_PREFIX)) {
            throw invalid(
                    lockPackage,
                    "`" + field + "` must start with `" + PATH_PREFIX + "`",
                    migrationCommand);
        }
        String suffix = recordedPath.substring(PATH_PREFIX.length());
        int separator = suffix.indexOf('/');
        if (separator != 64 || separator == suffix.length() - 1 || suffix.indexOf('/', separator + 1) >= 0) {
            throw invalid(
                    lockPackage,
                    "`" + field + "` must contain one SHA-256 directory and one artifact filename",
                    migrationCommand);
        }
        String pathChecksum = suffix.substring(0, separator);
        if (!SHA_256.matcher(pathChecksum).matches()) {
            throw invalid(
                    lockPackage,
                    "`" + field + "` digest directory must be exactly 64 lowercase hexadecimal characters",
                    migrationCommand);
        }
        String filename = suffix.substring(separator + 1);
        if (filename.isBlank()) {
            throw invalid(
                    lockPackage,
                    "`" + field + "` artifact filename must not be blank",
                    migrationCommand);
        }
        if (!pathChecksum.equals(recordedChecksum)) {
            throw invalid(
                    lockPackage,
                    "`" + field + "` digest directory must equal `" + field + "Sha256`",
                    migrationCommand);
        }
    }

    private static ActionableException invalid(
            LockPackage lockPackage,
            String violation,
            String migrationCommand) {
        return new ActionableException(ActionableError.of(
                "zolt.lock version "
                        + MINIMUM_VERSION
                        + " artifact contract is invalid for `"
                        + lockPackage.packageId()
                        + ":"
                        + lockPackage.version()
                        + "`: "
                        + violation
                        + ".",
                remediation(migrationCommand)));
    }

    private static String remediation(String migrationCommand) {
        return "Run `"
                + migrationCommand
                + "` once with this Zolt version to migrate zolt.lock, then retry the command.";
    }
}
