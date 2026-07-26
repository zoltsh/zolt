package sh.zolt.quality;

import static sh.zolt.quality.DependencyMetadataIdentity.packageId;
import static sh.zolt.quality.DependencyMetadataIdentity.scope;
import static sh.zolt.quality.DependencyMetadataIdentity.workspaceScope;
import static sh.zolt.quality.QualityCheckService.DEPENDENCY_METADATA;

import java.nio.file.Path;
import java.util.Optional;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

/** Validates one workspace declaration against its project edge and exact member lock evidence. */
final class WorkspaceDependencyMetadataQualityCheck {
    QualityCheckResult check(
            DependencyMetadata metadata,
            Workspace workspace,
            WorkspaceMember member,
            ZoltLockfile memberLock) {
        String memberPath = member.path();
        String target = normalizeMemberPath(metadata.workspace());
        DependencyScope dependencyScope = scope(metadata.section());
        boolean exported = "api.dependencies".equals(metadata.section());
        Optional<WorkspaceProjectEdge> maybeEdge = workspace.edges().stream()
                .filter(candidate -> candidate.from().equals(memberPath))
                .filter(candidate -> candidate.to().equals(target))
                .filter(candidate -> candidate.coordinate().equals(metadata.coordinate()))
                .filter(candidate -> candidate.scope().equals(workspaceScope(dependencyScope)))
                .findFirst();
        if (maybeEdge.isEmpty()) {
            return failed(
                    memberPath,
                    metadata.coordinate(),
                    "Workspace dependency `"
                            + metadata.coordinate()
                            + "` is not represented by the declared member, variant, and scope edge.",
                    "Run `zolt resolve --workspace`.");
        }
        WorkspaceProjectEdge edge = maybeEdge.orElseThrow();
        if (edge.exported() != exported) {
            return failed(
                    memberPath,
                    metadata.coordinate(),
                    exported
                            ? "Workspace API dependency `" + metadata.coordinate()
                                    + "` is not represented as an exported workspace edge."
                            : "Workspace implementation dependency `" + metadata.coordinate()
                                    + "` is incorrectly represented as exported.",
                    "Keep public workspace dependencies in [api.dependencies], implementation dependencies in [dependencies], and run `zolt resolve --workspace`.");
        }
        if (edge.optional() != metadata.optional()) {
            return failed(
                    memberPath,
                    metadata.coordinate(),
                    "Workspace dependency `"
                            + metadata.coordinate()
                            + "` declares optional = "
                            + metadata.optional()
                            + ", but its workspace edge records optional = "
                            + edge.optional()
                            + ".",
                    "Run `zolt resolve --workspace`.");
        }

        Optional<LockPackage> maybePackage = memberLock.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId(metadata.coordinate())))
                .filter(lockPackage -> LockArtifactVariant.of(lockPackage).isDefault())
                .filter(lockPackage -> lockPackage.scope() == dependencyScope)
                .filter(lockPackage -> lockPackage.workspace().orElse("").equals(target))
                .filter(LockPackage::direct)
                .findFirst();
        if (maybePackage.isEmpty()) {
            return failed(
                    memberPath,
                    metadata.coordinate(),
                    "Workspace dependency `"
                            + metadata.coordinate()
                            + "` is missing exact member, variant, and scope evidence in zolt.lock.",
                    "Run `zolt resolve --workspace`.");
        }
        LockPackage lockPackage = maybePackage.orElseThrow();
        boolean lockedOptional = new LockMemberGraphIndex(
                        memberLock.memberGraphs(), memberLock.packages())
                .optionalFor(memberPath, lockPackage);
        if (lockedOptional != metadata.optional()) {
            return failed(
                    memberPath,
                    metadata.coordinate(),
                    "Workspace dependency `"
                            + metadata.coordinate()
                            + "` declares optional = "
                            + metadata.optional()
                            + ", but member-qualified zolt.lock evidence records optional = "
                            + lockedOptional
                            + ".",
                    "Run `zolt resolve --workspace`.");
        }

        boolean exportedBy = lockPackage.exportedBy().contains(memberPath);
        if (exported && exportedBy == metadata.optional()) {
            return failed(
                    memberPath,
                    metadata.coordinate(),
                    metadata.optional()
                            ? "Optional workspace API dependency `" + metadata.coordinate()
                                    + "` incorrectly propagates through exportedBy in zolt.lock."
                            : "Workspace API dependency `" + metadata.coordinate()
                                    + "` is missing exportedBy ownership in zolt.lock.",
                    "Run `zolt resolve --workspace`.");
        }

        String message;
        if (exported && metadata.optional()) {
            message = "Optional workspace API dependency `"
                    + metadata.coordinate()
                    + "` is exported in the published model without propagating across workspace classpaths.";
        } else if (exported) {
            message = "Workspace API dependency `" + metadata.coordinate() + "` is exported through zolt.lock.";
        } else {
            message = "Workspace dependency `"
                    + metadata.coordinate()
                    + "` is represented by exact member, variant, scope, and optional evidence in zolt.lock.";
        }
        return QualityCheckResult.passed(
                DEPENDENCY_METADATA,
                Optional.of(memberPath),
                metadata.coordinate(),
                message);
    }

    private static QualityCheckResult failed(
            String member,
            String subject,
            String message,
            String nextStep) {
        return QualityCheckResult.failed(
                DEPENDENCY_METADATA,
                Optional.of(member),
                subject,
                message,
                nextStep);
    }

    private static String normalizeMemberPath(String path) {
        String normalized = Path.of(path).normalize().toString().replace('\\', '/');
        return normalized.isBlank() ? "." : normalized;
    }
}
