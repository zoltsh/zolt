package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;

public record PackagePlanLiveInput(String kind, String fingerprint) {
    public PackagePlanLiveInput {
        if (kind == null || kind.isBlank()) {
            throw new PackageException("Package plan live input kind is required.");
        }
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new PackageException("Package plan live input fingerprint is required.");
        }
    }
}
