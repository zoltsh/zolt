package sh.zolt.resolve;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;

/** A dependency mediation lane identified by Maven GA and artifact variant. */
public record ResolutionVariant(PackageId packageId, LockArtifactVariant artifactVariant) {
}
