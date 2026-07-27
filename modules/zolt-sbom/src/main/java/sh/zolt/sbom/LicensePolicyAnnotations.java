package sh.zolt.sbom;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.ProjectConfig;

/**
 * Per-coordinate license-policy status for a {@link LicenseReport}, so {@code zolt licenses} can show
 * what the configured {@code [dependencyPolicy.licenses]} makes of each dependency.
 *
 * <p>This is a reporting view only: verdicts come from {@link LicensePolicyEvaluator}, the same
 * evaluator {@code zolt check --check license-policy} enforces with, and nothing here fails a command.
 * When no policy is configured the annotations are {@link #none() unconfigured} and every renderer
 * emits exactly what it emitted before.
 *
 * <p>Across a workspace each member owns its own policy ({@code [dependencyPolicy]} is member-local,
 * not merged down from the root) while the report aggregates every member's dependencies. A coordinate
 * is therefore annotated with the strictest verdict any member's policy gives it, matching what a
 * workspace-wide enforcement run would flag.
 */
public record LicensePolicyAnnotations(
        boolean configured,
        Map<String, LicensePolicyFinding> findings,
        int evaluated) {
    public LicensePolicyAnnotations {
        findings = Map.copyOf(findings);
    }

    /** No policy configured: renderers stay byte-for-byte on their unannotated output. */
    public static LicensePolicyAnnotations none() {
        return new LicensePolicyAnnotations(false, Map.of(), 0);
    }

    /**
     * Evaluates every configured policy among {@code configs} against the report's components. Configs
     * whose policy is unset contribute nothing; when none is configured the result is {@link #none()}.
     */
    public static LicensePolicyAnnotations evaluate(
            List<SbomComponent> components,
            LicenseIndex index,
            List<ProjectConfig> configs) {
        List<LicensePolicySettings> policies = configs.stream()
                .map(config -> config.dependencyPolicy().licenses())
                .filter(policy -> !policy.isDefault())
                .toList();
        if (policies.isEmpty()) {
            return none();
        }
        LicensePolicyEvaluator evaluator = new LicensePolicyEvaluator();
        Map<String, LicensePolicyFinding> strictest = new LinkedHashMap<>();
        for (LicensePolicySettings policy : policies) {
            for (LicensePolicyFinding finding : evaluator.evaluate(components, index, policy)) {
                strictest.merge(finding.coordinate(), finding, LicensePolicyAnnotations::stricter);
            }
        }
        return new LicensePolicyAnnotations(true, strictest, components.size());
    }

    public Optional<LicensePolicyFinding> forCoordinate(String coordinate) {
        return Optional.ofNullable(findings.get(coordinate));
    }

    /** The renderer-facing status word for a coordinate: {@code denied}, {@code unknown}, or none. */
    public Optional<String> statusFor(String coordinate) {
        return forCoordinate(coordinate).map(finding -> status(finding.verdict()));
    }

    public int denied() {
        return count(LicenseVerdict.VIOLATION);
    }

    public int unknown() {
        return count(LicenseVerdict.WARN);
    }

    static String status(LicenseVerdict verdict) {
        return verdict == LicenseVerdict.VIOLATION ? "denied" : "unknown";
    }

    private int count(LicenseVerdict verdict) {
        return (int) findings.values().stream().filter(finding -> finding.verdict() == verdict).count();
    }

    private static LicensePolicyFinding stricter(LicensePolicyFinding current, LicensePolicyFinding candidate) {
        return candidate.verdict().ordinal() > current.verdict().ordinal() ? candidate : current;
    }
}
