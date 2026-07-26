package sh.zolt.build.packageevidence;

import sh.zolt.build.PackageException;

public record PackageEvidenceMaterializedInput(
        String coordinate,
        String sourceIdentity,
        String sourceFingerprint,
        String jar,
        String sha256) {
    public PackageEvidenceMaterializedInput {
        require(coordinate, "coordinate");
        require(sourceIdentity, "source identity");
        require(sourceFingerprint, "source fingerprint");
        require(jar, "jar path");
        require(sha256, "jar checksum");
    }

    private static void require(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new PackageException(
                    "Package evidence materialized input " + description + " is required.");
        }
    }
}
