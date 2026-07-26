package sh.zolt.classpath;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The complete identity of an artifact that can be nested in a package archive.
 *
 * <p>Variant identity deliberately excludes dependency scope: scope decides placement, while the
 * Maven artifact variant decides whether two entries contain the same artifact. Source kind remains
 * part of the full identity and nested filename so an external artifact and workspace provider can
 * never overwrite one another's staged or archived bytes.
 */
public record NestedArtifactIdentity(
        String groupId,
        String artifactId,
        String version,
        String extension,
        Optional<String> classifier,
        SourceKind sourceKind) {
    private static final int HASH_CHARACTERS = 24;

    public NestedArtifactIdentity {
        groupId = require(groupId, "groupId");
        artifactId = require(artifactId, "artifactId");
        version = require(version, "version");
        extension = require(extension, "extension");
        classifier = classifier == null ? Optional.empty() : classifier;
        classifier.ifPresent(value -> require(value, "classifier"));
        sourceKind = sourceKind == null ? SourceKind.EXTERNAL : sourceKind;
    }

    public static NestedArtifactIdentity of(LockPackage lockPackage) {
        LockArtifactVariant variant = LockArtifactVariant.of(lockPackage);
        return new NestedArtifactIdentity(
                lockPackage.packageId().groupId(),
                lockPackage.packageId().artifactId(),
                lockPackage.version(),
                variant.extension(),
                variant.classifier(),
                lockPackage.workspace().isPresent()
                        ? SourceKind.WORKSPACE
                        : SourceKind.EXTERNAL);
    }

    public static NestedArtifactIdentity external(PackageId packageId, String version) {
        return of(packageId, version, LockArtifactVariant.defaultVariant(), SourceKind.EXTERNAL);
    }

    public static NestedArtifactIdentity workspace(PackageId packageId, String version) {
        return of(packageId, version, LockArtifactVariant.defaultVariant(), SourceKind.WORKSPACE);
    }

    public static NestedArtifactIdentity of(
            PackageId packageId,
            String version,
            LockArtifactVariant variant,
            SourceKind sourceKind) {
        return new NestedArtifactIdentity(
                packageId.groupId(),
                packageId.artifactId(),
                version,
                variant.extension(),
                variant.classifier(),
                sourceKind);
    }

    public PackageId packageId() {
        return new PackageId(groupId, artifactId);
    }

    /**
     * Scope-free Maven artifact identity used for provided overlap.
     */
    public String artifactVariantKey() {
        return canonical(
                groupId,
                artifactId,
                extension,
                classifier.orElse(""));
    }

    /**
     * Full identity used for deduplication, staging, cache metadata, and archive naming.
     */
    public String canonicalKey() {
        return canonical(
                groupId,
                artifactId,
                version,
                extension,
                classifier.orElse(""),
                sourceKind.configValue());
    }

    public String coordinate() {
        if ("jar".equals(extension) && classifier.isEmpty()) {
            return groupId + ":" + artifactId + ":" + version;
        }
        return groupId
                + ":"
                + artifactId
                + ":"
                + classifier.orElse("")
                + ":"
                + extension
                + ":"
                + version;
    }

    public String nestedJarName() {
        StringBuilder name = new StringBuilder();
        name.append(filenameComponent(artifactId))
                .append('-')
                .append(filenameComponent(version));
        classifier.ifPresent(value ->
                name.append('-').append(filenameComponent(value)));
        name.append('-')
                .append(shortHash(canonicalKey()))
                .append(".jar");
        return name.toString();
    }

    private static String canonical(String... values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            canonical.append(value.length()).append(':').append(value).append(';');
        }
        return canonical.toString();
    }

    private static String filenameComponent(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return normalized.isBlank() ? "artifact" : normalized;
    }

    private static String shortHash(String value) {
        try {
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
            return hash.substring(0, HASH_CHARACTERS);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Nested artifact " + name + " must not be blank.");
        }
        return value;
    }

    public enum SourceKind {
        EXTERNAL("external"),
        WORKSPACE("workspace");

        private final String configValue;

        SourceKind(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }
}
