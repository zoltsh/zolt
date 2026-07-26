package sh.zolt.build.packageevidence;

import sh.zolt.build.PackageException;

public record PackageEvidenceOutput(
        String kind,
        String path,
        String checksumKind,
        String sha256) {
    public PackageEvidenceOutput {
        if (kind == null || kind.isBlank()) {
            throw new PackageException("Package evidence output kind is required.");
        }
        if (path == null || path.isBlank()) {
            throw new PackageException("Package evidence output path is required.");
        }
        if (!"file".equals(checksumKind) && !"tree".equals(checksumKind)) {
            throw new PackageException(
                    "Package evidence output checksum kind must be `file` or `tree`.");
        }
        if (sha256 == null || sha256.isBlank()) {
            throw new PackageException("Package evidence output checksum is required.");
        }
    }
}
