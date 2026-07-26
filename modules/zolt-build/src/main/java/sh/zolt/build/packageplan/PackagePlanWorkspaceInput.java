package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import sh.zolt.classpath.NestedArtifactIdentity;
import java.nio.file.Path;

/**
 * A checkout-independent workspace-provider identity and its current compiled bytes.
 */
public record PackagePlanWorkspaceInput(
        String coordinate,
        String identity,
        NestedArtifactIdentity artifactIdentity,
        Path sourceDirectory,
        String fingerprint) {
    public PackagePlanWorkspaceInput {
        if (coordinate == null || coordinate.isBlank()) {
            throw new PackageException("Package plan workspace input coordinate is required.");
        }
        if (identity == null || identity.isBlank()) {
            throw new PackageException("Package plan workspace input identity is required.");
        }
        if (artifactIdentity == null) {
            throw new PackageException("Package plan workspace artifact identity is required.");
        }
        if (sourceDirectory == null) {
            throw new PackageException("Package plan workspace input source directory is required.");
        }
        sourceDirectory = sourceDirectory.toAbsolutePath().normalize();
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new PackageException("Package plan workspace input fingerprint is required.");
        }
    }
}
