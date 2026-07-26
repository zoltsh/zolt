package sh.zolt.build.packageevidence;

import sh.zolt.build.PackageException;

public record PackageEvidenceWorkspaceInput(
        String coordinate,
        String identity,
        String fingerprint) {
    public PackageEvidenceWorkspaceInput {
        require(coordinate, "coordinate");
        require(identity, "identity");
        require(fingerprint, "fingerprint");
    }

    private static void require(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new PackageException(
                    "Package evidence workspace input " + description + " is required.");
        }
    }
}
