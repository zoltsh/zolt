package sh.zolt.sbom;

import java.util.Optional;
import sh.zolt.lockfile.LockPackage;

/**
 * Derives SBOM artifact facts (purl, hash, coordinate) from a {@link LockPackage}. Shared by the
 * single-project and workspace assemblers so purl/classifier derivation stays in one place.
 */
public final class LockArtifacts {
    private LockArtifacts() {
    }

    static String coordinate(LockPackage lockPackage) {
        return lockPackage.packageId() + ":" + lockPackage.version();
    }

    public static String purl(LockPackage lockPackage) {
        return PurlWriter.purl(
                lockPackage.packageId().groupId(),
                lockPackage.packageId().artifactId(),
                lockPackage.version(),
                extension(lockPackage),
                classifier(lockPackage));
    }

    static Optional<SbomHash> hash(LockPackage lockPackage) {
        return lockPackage.artifactSha256()
                .or(lockPackage::jarSha256)
                .filter(value -> !value.isBlank())
                .map(value -> new SbomHash("SHA-256", value));
    }

    static boolean materialized(LockPackage lockPackage) {
        return lockPackage.jar().isPresent() || lockPackage.artifact().isPresent();
    }

    static String extension(LockPackage lockPackage) {
        if (lockPackage.artifactType().isPresent()) {
            return lockPackage.artifactType().orElseThrow();
        }
        return fileName(lockPackage).map(LockArtifacts::extensionOf).orElse("jar");
    }

    static Optional<String> classifier(LockPackage lockPackage) {
        Optional<String> fileName = fileName(lockPackage);
        if (fileName.isEmpty()) {
            return Optional.empty();
        }
        String prefix = lockPackage.packageId().artifactId() + "-" + lockPackage.version();
        String name = fileName.orElseThrow();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        if (base.length() > prefix.length() + 1 && base.startsWith(prefix) && base.charAt(prefix.length()) == '-') {
            return Optional.of(base.substring(prefix.length() + 1));
        }
        return Optional.empty();
    }

    private static Optional<String> fileName(LockPackage lockPackage) {
        return lockPackage.artifact().or(lockPackage::jar).map(LockArtifacts::lastSegment);
    }

    private static String lastSegment(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1) : "jar";
    }
}
