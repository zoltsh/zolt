package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Canonical identity and output contract consumed by package evidence, quality, and publish.
 */
public record PackagePlanEvidence(
        String inputFingerprint,
        String packageLockFingerprint,
        String resolutionFingerprint,
        String frameworkRulesIdentity,
        List<PackagePlanOutput> outputs) {
    public PackagePlanEvidence {
        require(inputFingerprint, "input fingerprint");
        require(packageLockFingerprint, "package-lock fingerprint");
        require(resolutionFingerprint, "resolution fingerprint");
        require(frameworkRulesIdentity, "framework rules identity");
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        if (outputs.isEmpty()) {
            throw new PackageException("Package plan evidence requires at least one output.");
        }
    }

    static PackagePlanEvidence unavailable(
            Path archivePath,
            Optional<Path> runtimeClasspathPath) {
        List<PackagePlanOutput> outputs = new ArrayList<>();
        outputs.add(new PackagePlanOutput("main", archivePath));
        runtimeClasspathPath.ifPresent(
                path -> outputs.add(new PackagePlanOutput("runtime-classpath", path)));
        return new PackagePlanEvidence(
                "unavailable",
                "unavailable",
                "unavailable",
                "unavailable",
                outputs);
    }

    private static void require(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new PackageException("Package plan evidence " + description + " is required.");
        }
    }
}
