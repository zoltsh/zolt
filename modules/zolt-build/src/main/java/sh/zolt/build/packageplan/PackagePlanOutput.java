package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import java.nio.file.Path;

/**
 * One file owned by a package operation and therefore required by package evidence.
 */
public record PackagePlanOutput(String kind, Path path) {
    public PackagePlanOutput {
        if (kind == null || kind.isBlank()) {
            throw new PackageException("Package plan output kind is required.");
        }
        if (path == null) {
            throw new PackageException("Package plan output path is required.");
        }
        path = path.toAbsolutePath().normalize();
    }
}
