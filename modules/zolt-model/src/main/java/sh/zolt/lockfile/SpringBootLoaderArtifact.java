package sh.zolt.lockfile;

import sh.zolt.dependency.PackageId;
import java.util.Optional;

/**
 * Exact identity of the Spring Boot loader artifact expanded into executable archives.
 *
 * <p>Classified and non-jar artifacts sharing the loader GA are ordinary dependency variants. They
 * must never satisfy package tooling or be consumed as the executable loader.
 */
public final class SpringBootLoaderArtifact {
    public static final PackageId PACKAGE_ID = new PackageId(
            "org.springframework.boot",
            "spring-boot-loader");

    private SpringBootLoaderArtifact() {
    }

    public static boolean isDefaultLoader(
            PackageId packageId,
            LockArtifactVariant variant) {
        return PACKAGE_ID.equals(packageId) && variant.isDefault();
    }

    public static boolean isDefaultLoader(LockPackage lockPackage) {
        return isDefaultLoader(
                lockPackage.packageId(),
                LockArtifactVariant.of(lockPackage));
    }

    public static boolean isDefaultLoader(
            PackageId packageId,
            String extension,
            Optional<String> classifier) {
        return PACKAGE_ID.equals(packageId)
                && "jar".equals(extension)
                && classifier.isEmpty();
    }
}
