package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import java.nio.file.Path;

/**
 * One file or directory owned by a package operation and therefore required by package evidence.
 *
 * <p>{@code artifactType} is blank for non-publishable outputs such as a runtime classpath sidecar
 * or a Quarkus fast-JAR layout.
 */
public record PackagePlanOutput(
        String kind,
        Path path,
        String checksumKind,
        String artifactType) {
    public PackagePlanOutput {
        if (kind == null || kind.isBlank()) {
            throw new PackageException("Package plan output kind is required.");
        }
        if (path == null) {
            throw new PackageException("Package plan output path is required.");
        }
        path = path.toAbsolutePath().normalize();
        if (!"file".equals(checksumKind) && !"tree".equals(checksumKind)) {
            throw new PackageException(
                    "Package plan output checksum kind must be `file` or `tree`.");
        }
        artifactType = artifactType == null ? "" : artifactType;
    }

    public PackagePlanOutput(String kind, Path path) {
        this(kind, path, "file", "");
    }

    public boolean publishArtifact() {
        return !artifactType.isBlank();
    }
}
