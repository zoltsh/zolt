package sh.zolt.build.packageevidence;

import sh.zolt.build.PackageException;

public record PackageEvidenceLiveInput(String kind, String fingerprint) {
    public PackageEvidenceLiveInput {
        if (kind == null || kind.isBlank()) {
            throw new PackageException("Package evidence live input kind is required.");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new PackageException("Package evidence live input fingerprint is required.");
        }
    }
}
