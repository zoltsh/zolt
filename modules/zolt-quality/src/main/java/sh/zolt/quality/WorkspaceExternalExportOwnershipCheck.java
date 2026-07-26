package sh.zolt.quality;

import static sh.zolt.quality.QualityCheckService.DEPENDENCY_METADATA;

import java.util.Optional;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.DependencyMetadata;

/** Audits exported API ownership for an exact external dependency in a member policy lock. */
final class WorkspaceExternalExportOwnershipCheck {
    Optional<QualityCheckResult> check(
            String member,
            DependencyMetadata metadata,
            LockPackage lockPackage) {
        boolean exportedByMember = lockPackage.exportedBy().contains(member);
        boolean requiredApi = "api.dependencies".equals(metadata.section()) && !metadata.optional();
        if (requiredApi && !exportedByMember) {
            return Optional.of(QualityCheckResult.failed(
                    DEPENDENCY_METADATA,
                    Optional.of(member),
                    metadata.coordinate(),
                    "Required API dependency `"
                            + metadata.coordinate()
                            + "` is missing exportedBy ownership for member `"
                            + member
                            + "` in zolt.lock.",
                    "Run `zolt resolve --workspace`."));
        }
        if (!requiredApi && exportedByMember) {
            String declaration = "api.dependencies".equals(metadata.section())
                    ? "Optional API dependency"
                    : "Dependency in [" + metadata.section() + "]";
            return Optional.of(QualityCheckResult.failed(
                    DEPENDENCY_METADATA,
                    Optional.of(member),
                    metadata.coordinate(),
                    declaration
                            + " `"
                            + metadata.coordinate()
                            + "` incorrectly propagates through exportedBy for member `"
                            + member
                            + "` in zolt.lock.",
                    "Run `zolt resolve --workspace`."));
        }
        return Optional.empty();
    }
}
