package sh.zolt.sbom;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p><strong>Enforcement scope.</strong> The annotation names an enforcing command, so it may only
 * cover what that command evaluates: {@link SbomScopeSelection#requiredOnly()}, compile and runtime.
 * A report may list wider scopes ({@code --include-test} and friends); those entries stay listed and
 * stay UNANNOTATED, because marking a test-only dependency {@code [denied]} would claim a violation
 * {@code zolt check --check license-policy} passes. The caller therefore hands in the enforcing-scope
 * closure, not the reported one, and a coordinate that is in both an enforced and an optional scope is
 * in the enforcing closure and is annotated.
 *
 * <p>Across a workspace each member owns its own policy ({@code [dependencyPolicy]} is member-local,
 * not merged down from the root) while the report aggregates every member's dependencies. Evaluation is
 * therefore scoped: a member's policy sees only the closure that member consumes, exactly as
 * {@code zolt check --workspace --check license-policy} enforces it. A coordinate takes the strictest
 * verdict among the members that actually consume it, and a member that does not depend on a coordinate
 * contributes nothing to it — so the report never claims a violation the enforcing command would pass.
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

    /** Single owner: one policy over the enforcing-scope dependencies that same owner consumes. */
    public static LicensePolicyAnnotations evaluate(
            List<SbomComponent> components,
            LicenseIndex index,
            ProjectConfig config) {
        return evaluate(components, index, List.of(new LicensePolicyScope(config, components)));
    }

    /**
     * Evaluates each scope's policy against that scope's own components only, then merges per coordinate
     * with the strictest verdict winning among the scopes that reach it. Scopes whose policy is unset
     * contribute nothing; when none is configured the result is {@link #none()}.
     *
     * <p>{@code enforced} is the closure the enforcing command actually evaluates — the caller's
     * {@link SbomScopeSelection#requiredOnly()} assembly, NOT the wider list the report renders. It fixes
     * two things: a finding for a coordinate outside that closure is dropped, so an optional-scope entry
     * is listed without a status the enforcing command would not raise, and {@code evaluated} counts
     * distinct enforced coordinates rather than the raw component-list length, matching the report's own
     * per-coordinate deduplication.
     */
    public static LicensePolicyAnnotations evaluate(
            List<SbomComponent> enforced,
            LicenseIndex index,
            List<LicensePolicyScope> scopes) {
        List<LicensePolicyScope> enforcing = scopes.stream()
                .filter(scope -> !scope.policy().isDefault())
                .toList();
        if (enforcing.isEmpty()) {
            return none();
        }
        Set<String> enforcedCoordinates = new LinkedHashSet<>();
        for (SbomComponent component : enforced) {
            enforcedCoordinates.add(coordinate(component));
        }
        LicensePolicyEvaluator evaluator = new LicensePolicyEvaluator();
        Map<String, LicensePolicyFinding> strictest = new LinkedHashMap<>();
        for (LicensePolicyScope scope : enforcing) {
            for (LicensePolicyFinding finding : evaluator.evaluate(scope.components(), index, scope.policy())) {
                if (enforcedCoordinates.contains(finding.coordinate())) {
                    strictest.merge(finding.coordinate(), finding, LicensePolicyAnnotations::stricter);
                }
            }
        }
        return new LicensePolicyAnnotations(true, strictest, enforcedCoordinates.size());
    }

    private static String coordinate(SbomComponent component) {
        return component.group() + ":" + component.name() + ":" + component.version();
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
