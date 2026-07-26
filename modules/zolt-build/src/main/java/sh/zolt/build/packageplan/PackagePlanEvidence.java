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
        String buildInputFingerprint,
        String applicationOutputFingerprint,
        String packageLockFingerprint,
        String resolutionFingerprint,
        String frameworkRulesIdentity,
        List<PackagePlanOutput> outputs,
        List<PackagePlanLiveInput> supplementalInputs,
        List<PackagePlanWorkspaceInput> workspaceInputs,
        List<PackagePlanMaterializedInput> materializedInputs) {
    public PackagePlanEvidence {
        require(inputFingerprint, "input fingerprint");
        require(buildInputFingerprint, "build input fingerprint");
        require(applicationOutputFingerprint, "application output fingerprint");
        require(packageLockFingerprint, "package-lock fingerprint");
        require(resolutionFingerprint, "resolution fingerprint");
        require(frameworkRulesIdentity, "framework rules identity");
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        supplementalInputs =
                supplementalInputs == null ? List.of() : List.copyOf(supplementalInputs);
        workspaceInputs =
                workspaceInputs == null ? List.of() : List.copyOf(workspaceInputs);
        materializedInputs =
                materializedInputs == null ? List.of() : List.copyOf(materializedInputs);
        if (outputs.isEmpty()) {
            throw new PackageException("Package plan evidence requires at least one output.");
        }
    }

    static PackagePlanEvidence unavailable(
            sh.zolt.project.PackageMode mode,
            Path archivePath,
            Optional<Path> runtimeClasspathPath) {
        List<PackagePlanOutput> outputs = new ArrayList<>();
        outputs.add(new PackagePlanOutput(
                "main",
                archivePath,
                "file",
                mode.configValue()));
        runtimeClasspathPath.ifPresent(
                path -> outputs.add(new PackagePlanOutput("runtime-classpath", path)));
        return new PackagePlanEvidence(
                "unavailable",
                "unavailable",
                "unavailable",
                "unavailable",
                "unavailable",
                "unavailable",
                outputs,
                List.of(),
                List.of(),
                List.of());
    }

    private static void require(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new PackageException("Package plan evidence " + description + " is required.");
        }
    }
}
