package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import java.nio.file.Path;

/**
 * One workspace directory that the current package plan requires as a staged nested JAR.
 */
public record PackagePlanMaterializedInput(
        String coordinate,
        String sourceIdentity,
        Path sourceDirectory,
        String sourceFingerprint,
        Path jarPath) {
    public PackagePlanMaterializedInput {
        if (coordinate == null || coordinate.isBlank()) {
            throw new PackageException("Package plan materialized input coordinate is required.");
        }
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            throw new PackageException("Package plan materialized input source identity is required.");
        }
        if (sourceDirectory == null || jarPath == null) {
            throw new PackageException("Package plan materialized input paths are required.");
        }
        sourceDirectory = sourceDirectory.toAbsolutePath().normalize();
        jarPath = jarPath.toAbsolutePath().normalize();
        if (sourceFingerprint == null || sourceFingerprint.isBlank()) {
            throw new PackageException("Package plan materialized input source fingerprint is required.");
        }
    }
}
