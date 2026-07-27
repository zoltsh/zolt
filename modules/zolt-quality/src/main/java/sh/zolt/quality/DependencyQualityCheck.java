package sh.zolt.quality;

import static sh.zolt.quality.QualityCheckService.DEPENDENCY_METADATA;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import sh.zolt.lockfile.LockMemberGraphIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.policy.DependencyPolicyReportService;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceSelection;

final class DependencyQualityCheck {
    private final ZoltLockfileReader lockfileReader;
    private final DependencyPolicyQualityCheck dependencyPolicyQualityCheck;
    private final WorkspaceDependencyMetadataQualityCheck workspaceMetadataCheck =
            new WorkspaceDependencyMetadataQualityCheck();
    private final WorkspaceExternalExportOwnershipCheck exportOwnershipCheck =
            new WorkspaceExternalExportOwnershipCheck();

    DependencyQualityCheck(
            ZoltLockfileReader lockfileReader,
            DependencyPolicyReportService dependencyPolicyReportService) {
        this.lockfileReader = lockfileReader;
        this.dependencyPolicyQualityCheck = new DependencyPolicyQualityCheck(
                lockfileReader,
                dependencyPolicyReportService);
    }
    List<QualityCheckResult> checkProjectMetadata(
            Optional<String> member,
            Path root,
            ProjectConfig config,
            boolean workspaceLockfile) {
        Path lockfilePath = root.resolve("zolt.lock");
        if (!Files.isRegularFile(lockfilePath)) {
            return List.of(QualityCheckResult.failed(
                    DEPENDENCY_METADATA,
                    member,
                    "zolt.lock",
                    (workspaceLockfile ? "Workspace zolt.lock" : "zolt.lock") + " is missing.",
                    workspaceLockfile ? "Run `zolt resolve --workspace`." : "Run `zolt resolve`."));
        }

        ZoltLockfile lockfile;
        try {
            lockfile = lockfileReader.read(lockfilePath);
        } catch (LockfileReadException exception) {
            return List.of(QualityCheckResult.failed(
                    DEPENDENCY_METADATA,
                    member,
                    "zolt.lock",
                    exception.getMessage(),
                    workspaceLockfile ? "Run `zolt resolve --workspace`." : "Run `zolt resolve`."));
        }

        return checkDependencyMetadataDeclarations(
                member,
                config,
                lockfile,
                workspaceLockfile,
                Optional.empty());
    }
    List<QualityCheckResult> checkWorkspaceMetadata(
            Workspace workspace,
            WorkspaceSelection selection,
            WorkspaceQualityProjection projection) {
        List<QualityCheckResult> results = new ArrayList<>();
        for (String memberPath : selection.includedMembers()) {
            WorkspaceMemberQualityView view = projection.member(memberPath);
            results.addAll(checkDependencyMetadataDeclarations(
                    Optional.of(memberPath),
                    view.effectiveConfig(),
                    view.policyLock(),
                    true,
                    Optional.of(new WorkspaceMetadataContext(
                            workspace,
                            view.member(),
                            view.policyLock()))));
        }
        if (results.isEmpty()) {
            results.add(QualityCheckResult.passed(
                    DEPENDENCY_METADATA,
                    Optional.empty(),
                    workspace.config().name(),
                    "No dependency metadata declarations require validation."));
        }
        return List.copyOf(results);
    }
    List<QualityCheckResult> checkPolicy(
            Optional<String> member,
            Path root,
            ProjectConfig config,
            Path lockfilePath,
            boolean workspaceLockfile) {
        return dependencyPolicyQualityCheck.check(member, root, config, lockfilePath, workspaceLockfile);
    }

    List<QualityCheckResult> checkProjectedPolicy(
            Optional<String> member,
            Path root,
            ProjectConfig config,
            ZoltLockfile projectedLock) {
        return dependencyPolicyQualityCheck.checkProjected(member, root, config, projectedLock);
    }

    private List<QualityCheckResult> checkDependencyMetadataDeclarations(
            Optional<String> member,
            ProjectConfig config,
            ZoltLockfile lockfile,
            boolean workspaceLockfile,
            Optional<WorkspaceMetadataContext> workspaceContext) {
        java.util.SortedMap<String, DependencyMetadata> declarations =
                WorkspaceDependencyMetadataDeclarations.all(config);
        if (declarations.isEmpty()) {
            return List.of(QualityCheckResult.passed(
                    DEPENDENCY_METADATA,
                    member,
                    config.project().name(),
                    "No dependency metadata declarations require validation."));
        }

        List<QualityCheckResult> results = new ArrayList<>();
        for (DependencyMetadata metadata : declarations.values()) {
            if ("platforms".equals(metadata.section())) {
                continue;
            }
            if (metadata.workspace() != null) {
                if (workspaceContext.isPresent()) {
                    WorkspaceMetadataContext context = workspaceContext.orElseThrow();
                    results.add(workspaceMetadataCheck.check(
                            metadata,
                            context.workspace(),
                            context.member(),
                            context.lockfile()));
                } else {
                    results.add(QualityCheckResult.failed(
                            DEPENDENCY_METADATA,
                            member,
                            metadata.coordinate(),
                            "Workspace dependency `"
                                    + metadata.coordinate()
                                    + "` requires member-qualified workspace graph evidence.",
                            "Run `zolt check --workspace --check dependency-metadata` from the workspace root."));
                }
                continue;
            }
            if (metadata.publishOnly()) {
                results.add(checkPublishOnlyMetadata(member, metadata, lockfile));
                continue;
            }
            results.add(checkClasspathMetadata(
                    member,
                    metadata,
                    lockfile,
                    workspaceLockfile,
                    workspaceContext.isPresent()));
        }
        if (results.isEmpty()) {
            results.add(QualityCheckResult.passed(
                    DEPENDENCY_METADATA,
                    member,
                    config.project().name(),
                    "No dependency metadata declarations require validation."));
        }
        return List.copyOf(results);
    }

    private QualityCheckResult checkPublishOnlyMetadata(
            Optional<String> member,
            DependencyMetadata metadata,
            ZoltLockfile lockfile) {
        Optional<LockPackage> lockPackage = DependencyMetadataIdentity.find(lockfile, metadata);
        if (lockPackage.isPresent()) {
            return QualityCheckResult.failed(
                    DEPENDENCY_METADATA,
                    member,
                    metadata.coordinate(),
                    "Publish-only dependency `" + metadata.coordinate() + "` is present in zolt.lock.",
                    "Run `zolt resolve`; if it remains, remove publishOnly = true or move the dependency to a normal classpath section.");
        }
        return QualityCheckResult.passed(
                DEPENDENCY_METADATA,
                member,
                metadata.coordinate(),
                "Publish-only dependency `" + metadata.coordinate() + "` is kept out of zolt.lock classpaths.");
    }

    private QualityCheckResult checkClasspathMetadata(
            Optional<String> member,
            DependencyMetadata metadata,
            ZoltLockfile lockfile,
            boolean workspaceLockfile,
            boolean requireOptionalEvidence) {
        Optional<LockPackage> maybeLockPackage = DependencyMetadataIdentity.find(lockfile, metadata);
        if (maybeLockPackage.isEmpty()) {
            String message = "Dependency metadata for `" + metadata.coordinate()
                    + "` is not represented in zolt.lock.";
            if (workspaceLockfile) {
                message = message.substring(0, message.length() - 1)
                        + " for variant `"
                        + DependencyMetadataIdentity.declaredVariant(metadata).key()
                        + "` and scope `"
                        + DependencyMetadataIdentity.scope(metadata.section()).lockfileName()
                        + "`.";
            }
            return QualityCheckResult.failed(
                    DEPENDENCY_METADATA,
                    member,
                    metadata.coordinate(),
                    message,
                    workspaceLockfile ? "Run `zolt resolve --workspace`." : "Run `zolt resolve`.");
        }

        LockPackage lockPackage = maybeLockPackage.orElseThrow();
        if (!lockPackage.direct()) {
            String message = metadata.optional()
                    ? "Optional direct dependency `" + metadata.coordinate()
                            + "` is not marked direct in zolt.lock."
                    : "Direct dependency `" + metadata.coordinate()
                            + "` is not marked direct in zolt.lock.";
            if (workspaceLockfile) {
                message = message.substring(0, message.length() - 1)
                        + " for variant `"
                        + DependencyMetadataIdentity.declaredVariant(metadata).key()
                        + "` and scope `"
                        + DependencyMetadataIdentity.scope(metadata.section()).lockfileName()
                        + "`.";
            }
            return QualityCheckResult.failed(
                    DEPENDENCY_METADATA,
                    member,
                    metadata.coordinate(),
                    message,
                    workspaceLockfile ? "Run `zolt resolve --workspace`." : "Run `zolt resolve`.");
        }
        if (requireOptionalEvidence) {
            String memberPath = member.orElseThrow();
            LockMemberGraphIndex graphIndex =
                    new LockMemberGraphIndex(lockfile.memberGraphs(), lockfile.packages());
            boolean declaredOptional =
                    graphIndex.declaredOptionalFor(memberPath, lockPackage);
            if (declaredOptional != metadata.optional()) {
                return QualityCheckResult.failed(
                        DEPENDENCY_METADATA,
                        member,
                        metadata.coordinate(),
                        "Dependency metadata for `"
                                + metadata.coordinate()
                                + "` declares optional = "
                                + metadata.optional()
                                + ", but member-qualified zolt.lock declaration evidence records declaredOptional = "
                                + declaredOptional
                                + " for variant `"
                                + DependencyMetadataIdentity.declaredVariant(metadata).key()
                                + "` and scope `"
                                + DependencyMetadataIdentity.scope(metadata.section()).lockfileName()
                                + "`.",
                        "Run `zolt resolve --workspace`.");
            }
            boolean optionalOnly =
                    graphIndex.optionalOnlyFor(memberPath, lockPackage);
            if (!metadata.optional() && optionalOnly) {
                return QualityCheckResult.failed(
                        DEPENDENCY_METADATA,
                        member,
                        metadata.coordinate(),
                        "Dependency metadata for `"
                                + metadata.coordinate()
                                + "` is declared required, but member-qualified zolt.lock evidence records optional-only reachability for variant `"
                                + DependencyMetadataIdentity.declaredVariant(metadata).key()
                                + "` and scope `"
                                + DependencyMetadataIdentity.scope(metadata.section()).lockfileName()
                                + "`.",
                        "Run `zolt resolve --workspace`.");
            }
        }
        if (workspaceLockfile && requireOptionalEvidence) {
            Optional<QualityCheckResult> exportOwnership =
                    exportOwnershipCheck.check(member.orElseThrow(), metadata, lockPackage);
            if (exportOwnership.isPresent()) {
                return exportOwnership.orElseThrow();
            }
        }

        for (sh.zolt.project.DependencyExclusionSpec exclusion : metadata.exclusions()) {
            sh.zolt.dependency.PackageId excluded =
                    DependencyMetadataIdentity.packageId(exclusion.coordinate());
            if (DependencyMetadataIdentity.containsDependency(lockPackage, excluded)
                    && !DependencyMetadataIdentity.recordsAppliedEdgeExclusion(
                            lockfile,
                            lockPackage,
                            excluded)) {
                return QualityCheckResult.failed(
                        DEPENDENCY_METADATA,
                        member,
                        metadata.coordinate(),
                        "Excluded dependency `"
                                + exclusion.coordinate()
                                + "` is still present on direct dependency `"
                                + metadata.coordinate()
                                + "` in zolt.lock.",
                        "Check [" + metadata.section() + "]." + metadata.coordinate() + ".exclusions and run "
                                + (workspaceLockfile ? "`zolt resolve --workspace`." : "`zolt resolve`."));
            }
        }

        String message = "Dependency metadata for `" + metadata.coordinate()
                + "` is represented in zolt.lock.";
        if (workspaceLockfile) {
            message = message.substring(0, message.length() - 1)
                    + " for variant `"
                    + DependencyMetadataIdentity.declaredVariant(metadata).key()
                    + "` and scope `"
                    + DependencyMetadataIdentity.scope(metadata.section()).lockfileName()
                    + "`.";
        }
        return QualityCheckResult.passed(
                DEPENDENCY_METADATA,
                member,
                metadata.coordinate(),
                message);
    }

    private record WorkspaceMetadataContext(
            Workspace workspace,
            WorkspaceMember member,
            ZoltLockfile lockfile) {
    }
}
