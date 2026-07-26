package sh.zolt.lockfile;

import java.util.Optional;

/**
 * Compares the material target represented by two lock packages.
 *
 * <p>Workspace outputs retain path identity. External repositories use verified byte identity:
 * mirror IDs are provenance, not different artifacts, when both the primary artifact and POM
 * SHA-256 values are present and equal.
 */
public final class LockPackageTargetEquivalence {
    private LockPackageTargetEquivalence() {
    }

    public static boolean sameTarget(
            LockPackage left,
            LockPackage right) {
        if (strictlyEqual(left, right)) {
            return true;
        }
        if (left.workspace().isPresent() || right.workspace().isPresent()) {
            return false;
        }
        Optional<String> leftArtifact = artifactHash(left);
        Optional<String> rightArtifact = artifactHash(right);
        return leftArtifact.isPresent()
                && leftArtifact.equals(rightArtifact)
                && left.pomSha256().isPresent()
                && left.pomSha256().equals(right.pomSha256());
    }

    private static boolean strictlyEqual(
            LockPackage left,
            LockPackage right) {
        return left.source().equals(right.source())
                && left.workspace().equals(right.workspace())
                && left.workspaceOutput().equals(right.workspaceOutput())
                && left.jar().equals(right.jar())
                && left.jarSha256().equals(right.jarSha256())
                && left.artifact().equals(right.artifact())
                && left.artifactSha256().equals(right.artifactSha256())
                && left.pom().equals(right.pom())
                && left.pomSha256().equals(right.pomSha256());
    }

    private static Optional<String> artifactHash(LockPackage lockPackage) {
        return LockArtifactVariant.of(lockPackage).extension().equals("jar")
                ? lockPackage.jarSha256()
                : lockPackage.artifactSha256();
    }
}
