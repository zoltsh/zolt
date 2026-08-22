package sh.zolt.quality;

import static sh.zolt.quality.QualityCheckService.DEPENDENCY_POLICY;

import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.policy.DependencyPolicyReport;
import sh.zolt.policy.DependencyPolicyReportException;
import sh.zolt.policy.DependencyPolicyReportService;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.dependency.ConflictSelectionReason;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class DependencyPolicyQualityCheck {
    private final ZoltLockfileReader lockfileReader;
    private final DependencyPolicyReportService dependencyPolicyReportService;

    DependencyPolicyQualityCheck(
            ZoltLockfileReader lockfileReader,
            DependencyPolicyReportService dependencyPolicyReportService) {
        this.lockfileReader = lockfileReader;
        this.dependencyPolicyReportService = dependencyPolicyReportService;
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            Path root,
            ProjectConfig config,
            Path lockfilePath,
            boolean workspaceLockfile) {
        if (!Files.isRegularFile(lockfilePath)) {
            return List.of(QualityCheckResult.failed(
                    DEPENDENCY_POLICY,
                    member,
                    "zolt.lock",
                    "Dependency policy diagnostics require zolt.lock.",
                    workspaceLockfile ? "Run `zolt resolve --workspace`." : "Run `zolt resolve`."));
        }

        ZoltLockfile lockfile;
        try {
            lockfile = lockfileReader.read(lockfilePath);
        } catch (LockfileReadException exception) {
            return List.of(QualityCheckResult.failed(
                    DEPENDENCY_POLICY,
                    member,
                    "zolt.lock",
                    exception.getMessage(),
                    workspaceLockfile
                            ? "Run `zolt resolve --workspace` to refresh dependency policy evidence."
                            : "Run `zolt resolve` to refresh dependency policy evidence."));
        }

        return evaluate(member, root, config, lockfile, workspaceLockfile);
    }

    List<QualityCheckResult> checkProjected(
            Optional<String> member,
            Path root,
            ProjectConfig effectiveConfig,
            ZoltLockfile memberLock) {
        return evaluate(member, root, effectiveConfig, memberLock, true);
    }

    private List<QualityCheckResult> evaluate(
            Optional<String> member,
            Path root,
            ProjectConfig config,
            ZoltLockfile lockfile,
            boolean workspace) {
        try {
            DependencyPolicyReport report = dependencyPolicyReportService.report(root, config, lockfile);
            List<QualityCheckResult> results = new ArrayList<>();
            results.add(summary(member, config, report));
            addConflictPolicyDiagnostics(results, member, config, lockfile, workspace);
            addConstraintDiagnostics(results, member, report, workspace);
            addExclusionDiagnostics(results, member, report);
            addDirectVersionDiagnostics(results, member, report, workspace);
            return List.copyOf(results);
        } catch (DependencyPolicyReportException exception) {
            return List.of(QualityCheckResult.failed(
                    DEPENDENCY_POLICY,
                    member,
                    "zolt.toml",
                    exception.getMessage(),
                    "Fix dependency policy coordinates, then run `zolt check --check dependency-policy` again."));
        }
    }

    private static QualityCheckResult summary(
            Optional<String> member,
            ProjectConfig config,
            DependencyPolicyReport report) {
        return QualityCheckResult.passed(
                DEPENDENCY_POLICY,
                member,
                config.project().name(),
                "Dependency policy baseline is explainable: "
                        + report.platforms().size()
                        + " "
                        + QualityCheckText.plural(report.platforms().size(), "platform", "platforms")
                        + ", "
                        + report.constraints().size()
                        + " "
                        + QualityCheckText.plural(report.constraints().size(), "constraint", "constraints")
                        + ", "
                        + report.exclusions().size()
                        + " "
                        + QualityCheckText.plural(report.exclusions().size(), "exclusion", "exclusions")
                        + ", and "
                        + report.directVersions().size()
                        + " direct explicit "
                        + QualityCheckText.plural(report.directVersions().size(), "version", "versions")
                        + ".");
    }

    /**
     * Design §9.11: {@code conflicts = "warn"} resolves and reports. The lock is the durable record of
     * what was mediated, so the machine-readable report of a warn policy is a severity-tagged check
     * result rather than a resolve-time-only message.
     */
    private static void addConflictPolicyDiagnostics(
            List<QualityCheckResult> results,
            Optional<String> member,
            ProjectConfig config,
            ZoltLockfile lockfile,
            boolean workspace) {
        if (!config.dependencyPolicy().warnOnVersionConflict()) {
            return;
        }
        List<String> mediated = lockfile.conflicts().stream()
                .filter(conflict -> conflict.reason() != ConflictSelectionReason.SELECTED_GRAPH)
                .filter(conflict -> member.isEmpty() || conflict.members().isEmpty()
                        || conflict.members().contains(member.orElseThrow()))
                .sorted(Comparator.comparing(conflict -> conflict.packageId().toString()))
                .map(conflict -> conflict.packageId()
                        + " selected "
                        + conflict.selectedVersion()
                        + ", requested "
                        + String.join(", ", conflict.requestedVersions()))
                .toList();
        if (mediated.isEmpty()) {
            return;
        }
        results.add(QualityCheckResult.warning(
                DEPENDENCY_POLICY,
                member,
                "[dependencies.policy].conflicts",
                "Dependency version conflicts were mediated under `conflicts = \"warn\"`: "
                        + String.join("; ", mediated)
                        + ".",
                "Align the conflicting versions with a [platforms] BOM, a direct dependency, or a "
                        + "[dependencies.constraints] strict constraint, then run `"
                        + resolveCommand(workspace)
                        + "` again; set `conflicts = \"fail\"` to make this an error."));
    }

    private static void addConstraintDiagnostics(
            List<QualityCheckResult> results,
            Optional<String> member,
            DependencyPolicyReport report,
            boolean workspace) {
        for (DependencyPolicyReport.ConstraintPolicyDiagnostic constraint : report.constraints()) {
            if ("conflict".equals(constraint.status())) {
                results.add(QualityCheckResult.failed(
                        DEPENDENCY_POLICY,
                        member,
                        "[dependencies.constraints]." + constraint.coordinate(),
                        "Strict constraint expected `"
                                + constraint.coordinate()
                                + "` version `"
                                + constraint.requestedVersion()
                                + "`, but zolt.lock selected `"
                                + constraint.selectedVersion().orElse("none")
                                + "`.",
                        "Run `"
                                + resolveCommand(workspace)
                                + "` after updating [dependencies.constraints], or change the strict constraint to the selected baseline."));
            } else if ("direct-override".equals(constraint.status())) {
                results.add(QualityCheckResult.failed(
                        DEPENDENCY_POLICY,
                        member,
                        "[dependencies.constraints]." + constraint.coordinate(),
                        "Strict constraint for `"
                                + constraint.coordinate()
                                + "` is overridden by a direct dependency version.",
                        "Align the direct dependency version with [dependencies.constraints], or remove the strict constraint if the direct override is intentional."));
            }
        }
    }

    private static void addExclusionDiagnostics(
            List<QualityCheckResult> results,
            Optional<String> member,
            DependencyPolicyReport report) {
        for (DependencyPolicyReport.ExclusionPolicyDiagnostic exclusion : report.exclusions()) {
            if ("direct-conflict".equals(exclusion.status())) {
                results.add(QualityCheckResult.failed(
                        DEPENDENCY_POLICY,
                        member,
                        "[dependencies.policy].deny " + exclusion.coordinate(),
                        "Dependency policy excludes `"
                                + exclusion.coordinate()
                                + "`, but that package is still a direct dependency.",
                        "Remove the direct dependency, or remove the exclusion if the dependency is intentional."));
            }
        }
    }

    private static void addDirectVersionDiagnostics(
            List<QualityCheckResult> results,
            Optional<String> member,
            DependencyPolicyReport report,
            boolean workspace) {
        for (DependencyPolicyReport.DirectVersionDiagnostic direct : report.directVersions()) {
            if ("not-selected".equals(direct.status())) {
                results.add(QualityCheckResult.failed(
                        DEPENDENCY_POLICY,
                        member,
                        "[" + DependencyMetadata.manifestSection(direct.section()) + "]." + direct.coordinate(),
                        "Direct dependency `"
                                + direct.coordinate()
                                + ":"
                                + direct.version()
                                + "` is declared, but zolt.lock did not select that version.",
                        "Run `"
                                + resolveCommand(workspace)
                                + "`, then review the selected version or update the direct dependency declaration."));
            }
        }
    }

    private static String resolveCommand(boolean workspace) {
        return workspace ? "zolt resolve --workspace" : "zolt resolve";
    }
}
