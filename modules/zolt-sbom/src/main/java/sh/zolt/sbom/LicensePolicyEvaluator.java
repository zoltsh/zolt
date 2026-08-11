package sh.zolt.sbom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import sh.zolt.license.SpdxExpression;
import sh.zolt.project.LicensePolicyException;
import sh.zolt.project.LicensePolicySettings;

/** Evaluates declared license expressions and scoped exceptions without fetching metadata. */
public final class LicensePolicyEvaluator {
    /** Compatibility view: every non-global decision, including permitted-by-exception. */
    public List<LicensePolicyFinding> evaluate(
            List<SbomComponent> components,
            LicenseIndex index,
            LicensePolicySettings policy) {
        return evaluateDetailed(components, index, policy).findings();
    }

    public LicensePolicyEvaluation evaluateDetailed(
            List<SbomComponent> components,
            LicenseIndex index,
            LicensePolicySettings policy) {
        List<LicensePolicyDecision> decisions = new ArrayList<>();
        Set<String> usedExceptions = new LinkedHashSet<>();
        Map<String, Set<String>> versionsByDependency = new TreeMap<>();
        for (SbomComponent component : components) {
            String dependency = component.group() + ":" + component.name();
            String coordinate = dependency + ":" + component.version();
            versionsByDependency
                    .computeIfAbsent(dependency, ignored -> new LinkedHashSet<>())
                    .add(component.version());
            List<SbomLicense> licenses = index.forCoordinate(coordinate);
            if (licenses.isEmpty()) {
                licenses = List.of(SbomLicense.unknown());
            }
            LicensePolicyDecision decision = dependencyDecision(
                    coordinate,
                    component.purl(),
                    dependency,
                    component.version(),
                    licenses,
                    policy);
            decisions.add(decision);
            usedExceptions.addAll(decision.usedExceptions());
        }
        List<LicenseExceptionAudit> audits = audits(policy, versionsByDependency, usedExceptions);
        Set<String> mismatched = versionMismatchedDependencies(audits);
        List<LicensePolicyFinding> findings = decisions.stream()
                .map(decision -> decision.afterVersionMismatchSuppression(policy, mismatched))
                .flatMap(Optional::stream)
                .filter(finding -> finding.verdict() != LicenseVerdict.PERMITTED)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        findings.sort(Comparator.comparing(LicensePolicyFinding::coordinate));
        return new LicensePolicyEvaluation(findings, audits, components.size());
    }

    private LicensePolicyDecision dependencyDecision(
            String coordinate,
            String purl,
            String dependency,
            String version,
            List<SbomLicense> licenses,
            LicensePolicySettings policy) {
        return licenses.stream()
                .map(license -> licenseDecision(coordinate, purl, dependency, version, license, policy))
                .min(Comparator.comparing((LicensePolicyDecision decision) -> decision.finding().verdict())
                        .thenComparing(decision -> decision.finding().declaration())
                        .thenComparing(decision -> decision.finding().license()))
                .orElseGet(() -> decision(unknownFinding(coordinate, purl, "UNKNOWN", "UNKNOWN", policy)));
    }

    private LicensePolicyDecision licenseDecision(
            String coordinate,
            String purl,
            String dependency,
            String version,
            SbomLicense license,
            LicensePolicySettings policy) {
        String declaration = license.label();
        return switch (license.status()) {
            case SPDX -> termDecision(
                    coordinate,
                    purl,
                    declaration,
                    dependency,
                    version,
                    new SpdxExpression.License(license.spdxId().orElseThrow()),
                    policy);
            case SPDX_EXPRESSION -> expressionDecision(
                    coordinate,
                    purl,
                    declaration,
                    dependency,
                    version,
                    license.expression().orElseThrow(),
                    policy);
            case UNMAPPED -> decision(unmappedFinding(coordinate, purl, declaration, policy));
            case UNKNOWN -> decision(unknownFinding(coordinate, purl, declaration, "UNKNOWN", policy));
        };
    }

    private LicensePolicyDecision expressionDecision(
            String coordinate,
            String purl,
            String declaration,
            String dependency,
            String version,
            SpdxExpression expression,
            LicensePolicySettings policy) {
        return switch (expression) {
            case SpdxExpression.License ignored -> termDecision(
                    coordinate, purl, declaration, dependency, version, expression, policy);
            case SpdxExpression.With ignored -> termDecision(
                    coordinate, purl, declaration, dependency, version, expression, policy);
            case SpdxExpression.And and -> LicensePolicyDecision.and(
                    expressionDecision(coordinate, purl, declaration, dependency, version, and.left(), policy),
                    expressionDecision(coordinate, purl, declaration, dependency, version, and.right(), policy));
            case SpdxExpression.Or or -> LicensePolicyDecision.or(
                    expressionDecision(coordinate, purl, declaration, dependency, version, or.left(), policy),
                    expressionDecision(coordinate, purl, declaration, dependency, version, or.right(), policy));
        };
    }

    private LicensePolicyDecision termDecision(
            String coordinate,
            String purl,
            String declaration,
            String dependency,
            String version,
            SpdxExpression term,
            LicensePolicySettings policy) {
        String canonical = term.canonical();
        if (policy.deny().contains(canonical)
                || term instanceof SpdxExpression.With with && policy.deny().contains(with.licenseId())) {
            return decision(finding(
                    coordinate,
                    purl,
                    declaration,
                    canonical,
                    LicenseVerdict.VIOLATION,
                    "denied by [dependencyPolicy.licenses].deny",
                    LicensePolicyFindingCause.GLOBAL_DENY));
        }
        if (policy.allow().isEmpty() || policy.allow().contains(canonical)) {
            return decision(finding(
                    coordinate,
                    purl,
                    declaration,
                    canonical,
                    LicenseVerdict.PERMITTED,
                    "",
                    LicensePolicyFindingCause.PERMITTED));
        }
        Optional<LicensePolicyException> exception = matchingException(policy, dependency, version, canonical);
        if (exception.isPresent()) {
            LicensePolicyException matched = exception.orElseThrow();
            String path = "[dependencyPolicy.licenses.exceptions.\"" + dependency + "\"]";
            LicensePolicyFinding finding = new LicensePolicyFinding(
                    coordinate,
                    purl,
                    declaration,
                    canonical,
                    LicenseVerdict.PERMITTED_BY_EXCEPTION,
                    "permitted by " + path,
                    Optional.of(new LicensePolicyExceptionMatch(dependency, version, matched.reason())),
                    LicensePolicyFindingCause.SCOPED_EXCEPTION);
            return LicensePolicyDecision.exception(finding, dependency);
        }
        return decision(finding(
                coordinate,
                purl,
                declaration,
                canonical,
                LicenseVerdict.VIOLATION,
                "not in [dependencyPolicy.licenses].allow",
                LicensePolicyFindingCause.ALLOW_LIST));
    }

    private static Optional<LicensePolicyException> matchingException(
            LicensePolicySettings policy,
            String dependency,
            String version,
            String term) {
        LicensePolicyException exception = policy.exceptions().get(dependency);
        if (exception == null
                || exception.version().filter(expected -> !expected.equals(version)).isPresent()
                || !exception.allow().contains(term)) {
            return Optional.empty();
        }
        return Optional.of(exception);
    }

    private LicensePolicyFinding unmappedFinding(
            String coordinate,
            String purl,
            String raw,
            LicensePolicySettings policy) {
        if (policy.deny().contains(raw)) {
            return finding(coordinate, purl, raw, raw, LicenseVerdict.VIOLATION,
                    "denied by [dependencyPolicy.licenses].deny",
                    LicensePolicyFindingCause.GLOBAL_DENY);
        }
        if (policy.allow().contains(raw)) {
            return finding(
                    coordinate,
                    purl,
                    raw,
                    raw,
                    LicenseVerdict.PERMITTED,
                    "",
                    LicensePolicyFindingCause.PERMITTED);
        }
        return unknownFinding(coordinate, purl, raw, raw, policy);
    }

    private LicensePolicyFinding unknownFinding(
            String coordinate,
            String purl,
            String declaration,
            String label,
            LicensePolicySettings policy) {
        return switch (policy.unknown()) {
            case FAIL -> finding(coordinate, purl, declaration, label, LicenseVerdict.VIOLATION,
                    "unrecognized license and [dependencyPolicy.licenses].unknown = fail",
                    LicensePolicyFindingCause.UNRECOGNIZED);
            case WARN -> finding(coordinate, purl, declaration, label, LicenseVerdict.WARN,
                    "unrecognized license ([dependencyPolicy.licenses].unknown = warn)",
                    LicensePolicyFindingCause.UNRECOGNIZED);
            case ALLOW -> finding(
                    coordinate,
                    purl,
                    declaration,
                    label,
                    LicenseVerdict.PERMITTED,
                    "",
                    LicensePolicyFindingCause.PERMITTED);
        };
    }

    private static LicensePolicyFinding finding(
            String coordinate,
            String purl,
            String declaration,
            String license,
            LicenseVerdict verdict,
            String reason,
            LicensePolicyFindingCause cause) {
        return new LicensePolicyFinding(
                coordinate, purl, declaration, license, verdict, reason, Optional.empty(), cause);
    }

    private static LicensePolicyDecision decision(LicensePolicyFinding finding) {
        return LicensePolicyDecision.of(finding);
    }

    private static List<LicenseExceptionAudit> audits(
            LicensePolicySettings policy,
            Map<String, Set<String>> versionsByDependency,
            Set<String> usedExceptions) {
        List<LicenseExceptionAudit> audits = new ArrayList<>();
        for (LicensePolicyException exception : policy.exceptions().values()) {
            Set<String> versions = versionsByDependency.get(exception.dependency());
            if (versions == null || versions.isEmpty()) {
                audits.add(new LicenseExceptionAudit(exception, LicenseExceptionAuditStatus.MISSING, Optional.empty()));
                continue;
            }
            Optional<String> configured = exception.version();
            if (configured.isPresent() && !versions.contains(configured.orElseThrow())) {
                audits.add(new LicenseExceptionAudit(
                        exception,
                        LicenseExceptionAuditStatus.VERSION_MISMATCHED,
                        versions.stream().sorted().findFirst()));
                continue;
            }
            LicenseExceptionAuditStatus status = usedExceptions.contains(exception.dependency())
                    ? LicenseExceptionAuditStatus.USED
                    : LicenseExceptionAuditStatus.REDUNDANT;
            String resolved = configured.orElseGet(() -> versions.stream().sorted().findFirst().orElseThrow());
            audits.add(new LicenseExceptionAudit(exception, status, Optional.of(resolved)));
        }
        return List.copyOf(audits);
    }

    private static Set<String> versionMismatchedDependencies(List<LicenseExceptionAudit> audits) {
        return audits.stream()
                .filter(audit -> audit.status() == LicenseExceptionAuditStatus.VERSION_MISMATCHED)
                .map(audit -> audit.exception().dependency())
                .collect(java.util.stream.Collectors.toSet());
    }
}
