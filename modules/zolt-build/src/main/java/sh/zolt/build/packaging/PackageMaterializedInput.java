package sh.zolt.build.packaging;

import sh.zolt.build.PackageException;
import java.nio.file.Path;

/**
 * A workspace classes directory materialized as a deterministic thin JAR for a nested-archive layout.
 */
public record PackageMaterializedInput(
        String coordinate,
        Path sourceDirectory,
        Path jarPath,
        String sha256) {
    public PackageMaterializedInput {
        if (coordinate == null || coordinate.isBlank()) {
            throw new PackageException("Materialized package input coordinate is required.");
        }
        if (sourceDirectory == null || jarPath == null) {
            throw new PackageException("Materialized package input paths are required.");
        }
        if (sha256 == null || sha256.isBlank()) {
            throw new PackageException("Materialized package input checksum is required.");
        }
    }
}
