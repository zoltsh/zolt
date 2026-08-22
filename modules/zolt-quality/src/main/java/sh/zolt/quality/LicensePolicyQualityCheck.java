package sh.zolt.quality;

import static sh.zolt.quality.QualityCheckService.LICENSE_POLICY;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.sbom.LicenseExceptionAudit;
import sh.zolt.sbom.LicenseIndex;
import sh.zolt.sbom.LicensePolicyEvaluator;
import sh.zolt.sbom.LicensePolicyEvaluation;
import sh.zolt.sbom.LicensePolicyFinding;
import sh.zolt.sbom.LicensePolicyFindingCause;
import sh.zolt.sbom.LicenseVerdict;
import sh.zolt.sbom.LockArtifacts;
import sh.zolt.sbom.LockSbomAssembler;
import sh.zolt.sbom.PomLicenseResolver;
import sh.zolt.sbom.SbomComponent;
import sh.zolt.sbom.SbomScopeGroup;
import sh.zolt.sbom.SbomScopeSelection;

/**
 * Offline license-policy gate for {@code zolt check}. Reads {@code [dependencies.policy.licenses]},
 * resolves the compile/runtime dependency licenses from cached POMs, and evaluates them: deny/allow
 * violations fail, UNKNOWN follows the configured strictness. Every failure names the dependency, the
 * license, and the policy line, with an actionable {@code Next:}.
 */
final class LicensePolicyQualityCheck {
    private final ZoltLockfileReader lockfileReader;
    private final LockSbomAssembler assembler;
    private final LicensePolicyEvaluator evaluator;

    LicensePolicyQualityCheck(ZoltLockfileReader lockfileReader) {
        this(lockfileReader, new LockSbomAssembler(), new LicensePolicyEvaluator());
    }

    LicensePolicyQualityCheck(
            ZoltLockfileReader lockfileReader,
            LockSbomAssembler assembler,
            LicensePolicyEvaluator evaluator) {
        this.lockfileReader = lockfileReader;
        this.assembler = assembler;
        this.evaluator = evaluator;
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            Path root,
            ProjectConfig config,
            Path lockfilePath,
            boolean workspaceLockfile,
            Path cacheRoot) {
        LicensePolicySettings policy = config.dependencyPolicy().licenses();
        if (policy.isDefault()) {
            return List.of(QualityCheckResult.skipped(
                    LICENSE_POLICY,
                    member,
                    "[dependencies.policy.licenses]",
                    "No license policy configured; nothing to enforce.",
                    "Add [dependencies.policy.licenses] allow/deny/unknown to enforce license compliance."));
        }
        if (!Files.isRegularFile(lockfilePath)) {
            return List.of(QualityCheckResult.failed(
                    LICENSE_POLICY,
                    member,
                    "zolt.lock",
                    "License policy diagnostics require zolt.lock.",
                    workspaceLockfile ? "Run `zolt resolve --workspace`." : "Run `zolt resolve`."));
        }

        ZoltLockfile lockfile;
        try {
            lockfile = lockfileReader.read(lockfilePath);
        } catch (LockfileReadException exception) {
            return List.of(QualityCheckResult.failed(
                    LICENSE_POLICY,
                    member,
                    "zolt.lock",
                    exception.getMessage(),
                    workspaceLockfile
                            ? "Run `zolt resolve --workspace` to refresh license evidence."
                            : "Run `zolt resolve` to refresh license evidence."));
        }

        return evaluate(member, config, lockfile, cacheRoot, false);
    }

    List<QualityCheckResult> checkProjected(
            Optional<String> member,
            ProjectConfig effectiveConfig,
            ZoltLockfile memberSbomLock,
            Path cacheRoot) {
        LicensePolicySettings policy = effectiveConfig.dependencyPolicy().licenses();
        if (policy.isDefault()) {
            return List.of(QualityCheckResult.skipped(
                    LICENSE_POLICY,
                    member,
                    "[dependencies.policy.licenses]",
                    "No license policy configured; nothing to enforce.",
                    "Add [dependencies.policy.licenses] allow/deny/unknown to enforce license compliance."));
        }
        return evaluate(member, effectiveConfig, memberSbomLock, cacheRoot, true);
    }

    private List<QualityCheckResult> evaluate(
            Optional<String> member,
            ProjectConfig config,
            ZoltLockfile lockfile,
            Path cacheRoot,
            boolean externalOnly) {
        LicensePolicySettings policy = config.dependencyPolicy().licenses();
        SbomScopeSelection selection = SbomScopeSelection.requiredOnly();
        List<LockPackage> external = lockfile.packages().stream()
                .filter(lockPackage -> selection.includes(SbomScopeGroup.of(lockPackage.scope())))
                .filter(lockPackage -> lockPackage.workspace().isEmpty())
                .toList();
        LicenseIndex index = new PomLicenseResolver(cacheRoot).index(
                external.stream().filter(lockPackage -> lockPackage.pom().isPresent()).toList());
        List<SbomComponent> assembled =
                assembler.assemble(config, lockfile, selection, Optional.empty(), "zolt", index).components();
        Set<String> externalPurls = external.stream()
                .map(LockArtifacts::purl)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<SbomComponent> components = externalOnly
                ? assembled.stream()
                        .filter(component -> externalPurls.contains(component.purl()))
                        .toList()
                : assembled;
        LicensePolicyEvaluation evaluation = evaluator.evaluateDetailed(components, index, policy);
        List<LicensePolicyFinding> findings = evaluation.findings();

        List<QualityCheckResult> results = new ArrayList<>();
        results.add(summary(member, components.size(), evaluation));
        for (LicensePolicyFinding finding : findings) {
            String message = finding.license() + " — " + finding.reason();
            switch (finding.verdict()) {
                case VIOLATION -> results.add(QualityCheckResult.failed(
                        LICENSE_POLICY, member, finding.coordinate(), message, nextStep(finding)));
                case WARN -> results.add(QualityCheckResult.warning(
                        LICENSE_POLICY, member, finding.coordinate(), message, nextStep(finding)));
                case PERMITTED, PERMITTED_BY_EXCEPTION -> {
                    // The summary records permitted decisions; only failures and warnings need details.
                }
            }
        }
        evaluation.exceptionAudits().stream()
                .filter(LicenseExceptionAudit::failure)
                .map(audit -> exceptionFailure(member, audit))
                .forEach(results::add);
        return List.copyOf(results);
    }

    private static QualityCheckResult summary(
            Optional<String> member, int total, LicensePolicyEvaluation evaluation) {
        long violations = evaluation.findings().stream()
                .filter(finding -> finding.verdict() == LicenseVerdict.VIOLATION)
                .count();
        long warnings = evaluation.findings().stream()
                .filter(finding -> finding.verdict() == LicenseVerdict.WARN)
                .count();
        long exceptions = evaluation.findings().stream()
                .filter(finding -> finding.verdict() == LicenseVerdict.PERMITTED_BY_EXCEPTION)
                .count();
        long stale = evaluation.exceptionAudits().stream().filter(LicenseExceptionAudit::failure).count();
        String message = "Evaluated " + total + " compile/runtime "
                + QualityCheckText.plural(total, "dependency", "dependencies")
                + " against [dependencies.policy.licenses]: " + violations + " violation(s), "
                + warnings + " warning(s). " + exceptions + " permitted by exception, "
                + stale + " stale exception(s).";
        return QualityCheckResult.passed(LICENSE_POLICY, member, "[dependencies.policy.licenses]", message);
    }

    private static String nextStep(LicensePolicyFinding finding) {
        if (finding.cause() == LicensePolicyFindingCause.GLOBAL_DENY) {
            return "Remove " + finding.coordinate()
                    + " or amend [dependencies.policy.licenses].deny after review; an exception cannot override deny.";
        }
        if (finding.cause() == LicensePolicyFindingCause.UNRECOGNIZED) {
            return "Run `zolt resolve`, verify the dependency's cached POM license metadata, or amend "
                    + "[dependencies.policy.licenses].unknown after review; scoped exceptions require SPDX terms.";
        }
        return "Remove " + finding.coordinate()
                + ", add `" + finding.license() + "` to [dependencies.policy.licenses].allow, or add an exact reviewed "
                + "[dependencies.license-exceptions.\"group:artifact\"] entry.";
    }

    private static QualityCheckResult exceptionFailure(
            Optional<String> member,
            LicenseExceptionAudit audit) {
        String dependency = audit.exception().dependency();
        String path = "[dependencies.license-exceptions.\"" + dependency + "\"]";
        String message = switch (audit.status()) {
            case MISSING -> "Scoped license exception is stale: " + dependency
                    + " is absent from the compile/runtime dependency closure.";
            case VERSION_MISMATCHED -> "Scoped license exception reviewed version "
                    + audit.exception().version().orElseThrow() + ", but the resolved version is "
                    + audit.resolvedVersion().orElse("unknown") + ".";
            case REDUNDANT -> "Scoped license exception is redundant: no resolved declaration requires it.";
            case USED -> throw new IllegalArgumentException("used exceptions are not failures");
        };
        return QualityCheckResult.failed(
                LICENSE_POLICY,
                member,
                path,
                message + " Reason: " + audit.exception().reason(),
                "Remove " + path + " or update it after reviewing the resolved dependency and license evidence.");
    }
}
