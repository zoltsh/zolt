package sh.zolt.sbom;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import sh.zolt.project.LicensePolicyException;
import sh.zolt.project.LicensePolicySettings;

/** Internal expression decision that retains every AND branch until audit suppression is complete. */
record LicensePolicyDecision(
        List<LicensePolicyFinding> candidates,
        Set<String> usedExceptions) {
    LicensePolicyDecision {
        candidates = List.copyOf(candidates);
        usedExceptions = Set.copyOf(usedExceptions);
    }

    static LicensePolicyDecision of(LicensePolicyFinding finding) {
        return new LicensePolicyDecision(List.of(finding), Set.of());
    }

    static LicensePolicyDecision exception(LicensePolicyFinding finding, String dependency) {
        return new LicensePolicyDecision(List.of(finding), Set.of(dependency));
    }

    static LicensePolicyDecision and(LicensePolicyDecision left, LicensePolicyDecision right) {
        List<LicensePolicyFinding> candidates = new ArrayList<>(left.candidates());
        candidates.addAll(right.candidates());
        Set<String> used = new LinkedHashSet<>(left.usedExceptions());
        used.addAll(right.usedExceptions());
        return new LicensePolicyDecision(candidates, used);
    }

    static LicensePolicyDecision or(LicensePolicyDecision left, LicensePolicyDecision right) {
        return better(left.finding(), right.finding()) == left.finding() ? left : right;
    }

    LicensePolicyFinding finding() {
        return candidates.stream().reduce(LicensePolicyDecision::stricter).orElseThrow();
    }

    Optional<LicensePolicyFinding> afterVersionMismatchSuppression(
            LicensePolicySettings policy,
            Set<String> mismatched) {
        return candidates.stream()
                .filter(finding -> !isVersionMismatchDuplicate(finding, policy, mismatched))
                .reduce(LicensePolicyDecision::stricter);
    }

    static LicensePolicyFinding stricter(LicensePolicyFinding left, LicensePolicyFinding right) {
        int verdict = left.verdict().compareTo(right.verdict());
        if (verdict != 0) {
            return verdict > 0 ? left : right;
        }
        int cause = Integer.compare(left.cause().precedence(), right.cause().precedence());
        if (cause != 0) {
            return cause > 0 ? left : right;
        }
        return left.license().compareTo(right.license()) <= 0 ? left : right;
    }

    private static LicensePolicyFinding better(LicensePolicyFinding left, LicensePolicyFinding right) {
        int verdict = left.verdict().compareTo(right.verdict());
        if (verdict != 0) {
            return verdict < 0 ? left : right;
        }
        return left.license().compareTo(right.license()) <= 0 ? left : right;
    }

    private static boolean isVersionMismatchDuplicate(
            LicensePolicyFinding finding,
            LicensePolicySettings policy,
            Set<String> mismatched) {
        String dependency = finding.coordinate().substring(0, finding.coordinate().lastIndexOf(':'));
        LicensePolicyException exception = policy.exceptions().get(dependency);
        return mismatched.contains(dependency)
                && exception != null
                && exception.allow().contains(finding.license())
                && finding.cause() == LicensePolicyFindingCause.ALLOW_LIST;
    }
}
