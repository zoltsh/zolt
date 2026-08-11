package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.license.SpdxExpressionParser;
import sh.zolt.project.LicensePolicyException;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.UnknownLicensePolicy;

final class LicensePolicyEvaluatorTest {
    private final LicensePolicyEvaluator evaluator = new LicensePolicyEvaluator();

    @Test
    void denyAlwaysWinsEvenWhenAlsoAllowed() {
        LicensePolicyFinding finding = onlyFinding(
                spdx("GPL-3.0-only"),
                new LicensePolicySettings(List.of("GPL-3.0-only"), List.of("GPL-3.0-only"), UnknownLicensePolicy.WARN));
        assertEquals(LicenseVerdict.VIOLATION, finding.verdict());
        assertTrue(finding.reason().contains("deny"));
    }

    @Test
    void nonEmptyAllowListIsAuthoritative() {
        LicensePolicyFinding finding = onlyFinding(
                spdx("MIT"),
                new LicensePolicySettings(List.of("Apache-2.0"), List.of(), UnknownLicensePolicy.WARN));
        assertEquals(LicenseVerdict.VIOLATION, finding.verdict());
        assertTrue(finding.reason().contains("allow"));
    }

    @Test
    void permittedWhenNotDeniedAndAllowEmpty() {
        assertTrue(findings(spdx("MIT"), LicensePolicySettings.defaults()).isEmpty());
    }

    @Test
    void permittedWhenInAllowList() {
        assertTrue(findings(
                spdx("Apache-2.0"),
                new LicensePolicySettings(List.of("Apache-2.0"), List.of(), UnknownLicensePolicy.WARN)).isEmpty());
    }

    @Test
    void unmappedMatchesByRawStringInDeny() {
        LicensePolicyFinding finding = onlyFinding(
                unmapped("Weird License"),
                new LicensePolicySettings(List.of(), List.of("Weird License"), UnknownLicensePolicy.WARN));
        assertEquals(LicenseVerdict.VIOLATION, finding.verdict());
    }

    @Test
    void unmappedMatchesByRawStringInAllow() {
        assertTrue(findings(
                unmapped("Weird License"),
                new LicensePolicySettings(List.of("Weird License"), List.of(), UnknownLicensePolicy.WARN)).isEmpty());
    }

    @Test
    void unlistedUnmappedFollowsUnknownStrictness() {
        assertEquals(LicenseVerdict.WARN, onlyFinding(unmapped("Weird License"),
                policy(UnknownLicensePolicy.WARN)).verdict());
        assertEquals(LicenseVerdict.VIOLATION, onlyFinding(unmapped("Weird License"),
                policy(UnknownLicensePolicy.FAIL)).verdict());
        assertTrue(findings(unmapped("Weird License"), policy(UnknownLicensePolicy.ALLOW)).isEmpty());
    }

    @Test
    void unknownLicenseFollowsUnknownStrictnessMatrix() {
        assertEquals(LicenseVerdict.WARN, onlyFinding(unknown(), policy(UnknownLicensePolicy.WARN)).verdict());
        assertEquals(LicenseVerdict.VIOLATION, onlyFinding(unknown(), policy(UnknownLicensePolicy.FAIL)).verdict());
        assertTrue(findings(unknown(), policy(UnknownLicensePolicy.ALLOW)).isEmpty());
    }

    @Test
    void dualLicensePermittedWhenAnyOptionPermitted() {
        assertTrue(findings(
                List.of(SbomLicense.spdx("GPL-3.0-only"), SbomLicense.spdx("MIT")),
                new LicensePolicySettings(List.of(), List.of("GPL-3.0-only"), UnknownLicensePolicy.WARN)).isEmpty());
    }

    @Test
    void dualLicenseViolatesWhenEveryOptionViolates() {
        LicensePolicyFinding finding = onlyFinding(
                List.of(SbomLicense.spdx("GPL-3.0-only"), SbomLicense.spdx("AGPL-3.0-only")),
                new LicensePolicySettings(
                        List.of(), List.of("GPL-3.0-only", "AGPL-3.0-only"), UnknownLicensePolicy.WARN));
        assertEquals(LicenseVerdict.VIOLATION, finding.verdict());
    }

    @Test
    void andExpressionUsesExactScopedExceptionForItsStrictestTerm() {
        LicensePolicySettings policy = exceptionPolicy(
                List.of("MIT"), List.of(), Optional.of("1.0.0"), List.of("BSD-3-Clause"));

        LicensePolicyEvaluation evaluation = detailed(
                expression("MIT AND BSD-3-Clause"), policy, component());

        assertEquals(1, evaluation.findings().size());
        LicensePolicyFinding finding = evaluation.findings().getFirst();
        assertEquals(LicenseVerdict.PERMITTED_BY_EXCEPTION, finding.verdict());
        assertEquals("MIT AND BSD-3-Clause", finding.declaration());
        assertEquals("BSD-3-Clause", finding.license());
        assertEquals("Reviewed dependency", finding.exceptionMatch().orElseThrow().reason());
        assertEquals(LicenseExceptionAuditStatus.USED, evaluation.exceptionAudits().getFirst().status());
    }

    @Test
    void exceptionNeverPermitsTheSameLicenseOnAnotherDependency() {
        LicensePolicySettings policy = exceptionPolicy(
                List.of("MIT"), List.of(), Optional.empty(), List.of("BSD-3-Clause"));
        SbomComponent other = component("org.other", "lib", "1.0.0");

        LicensePolicyEvaluation evaluation = detailed(SbomLicense.spdx("BSD-3-Clause"), policy, other);

        assertEquals(LicenseVerdict.VIOLATION, evaluation.findings().getFirst().verdict());
        assertEquals(LicenseExceptionAuditStatus.MISSING, evaluation.exceptionAudits().getFirst().status());
    }

    @Test
    void orPrefersGlobalAllowanceAndLeavesUnneededExceptionRedundant() {
        LicensePolicySettings policy = exceptionPolicy(
                List.of("MIT"), List.of(), Optional.empty(), List.of("BSD-3-Clause"));

        LicensePolicyEvaluation evaluation = detailed(expression("MIT OR BSD-3-Clause"), policy, component());

        assertTrue(evaluation.findings().isEmpty());
        assertEquals(LicenseExceptionAuditStatus.REDUNDANT, evaluation.exceptionAudits().getFirst().status());
    }

    @Test
    void andRetainsExceptionUseFromANonDecisiveBranch() {
        LicensePolicySettings policy = exceptionPolicy(
                List.of("MIT"), List.of(), Optional.empty(), List.of("BSD-3-Clause"));

        LicensePolicyEvaluation evaluation = detailed(
                expression("BSD-3-Clause AND GPL-3.0-only"), policy, component());

        assertEquals(LicenseVerdict.VIOLATION, evaluation.findings().getFirst().verdict());
        assertEquals(LicenseExceptionAuditStatus.USED, evaluation.exceptionAudits().getFirst().status());
    }

    @Test
    void denyOfBaseLicenseWinsForWithTermEvenWhenExceptionAllowsIt() {
        String term = "GPL-2.0-only WITH Classpath-exception-2.0";
        LicensePolicySettings policy = exceptionPolicy(
                List.of("MIT"), List.of("GPL-2.0-only"), Optional.empty(), List.of(term));

        LicensePolicyFinding finding = detailed(expression(term), policy, component()).findings().getFirst();

        assertEquals(LicenseVerdict.VIOLATION, finding.verdict());
        assertTrue(finding.reason().contains("deny"));
    }

    @Test
    void versionMismatchIsOneAuditFailureInsteadOfADuplicateLicenseFinding() {
        LicensePolicySettings policy = exceptionPolicy(
                List.of("MIT"), List.of(), Optional.of("0.9.0"), List.of("BSD-3-Clause"));

        LicensePolicyEvaluation evaluation = detailed(SbomLicense.spdx("BSD-3-Clause"), policy, component());

        assertTrue(evaluation.findings().isEmpty());
        LicenseExceptionAudit audit = evaluation.exceptionAudits().getFirst();
        assertEquals(LicenseExceptionAuditStatus.VERSION_MISMATCHED, audit.status());
        assertEquals(Optional.of("1.0.0"), audit.resolvedVersion());
    }

    private static LicensePolicySettings policy(UnknownLicensePolicy unknown) {
        return new LicensePolicySettings(List.of(), List.of(), unknown);
    }

    private List<LicensePolicyFinding> findings(SbomLicense license, LicensePolicySettings policy) {
        return findings(List.of(license), policy);
    }

    private List<LicensePolicyFinding> findings(List<SbomLicense> licenses, LicensePolicySettings policy) {
        String coordinate = "org.example:lib:1.0.0";
        LicenseIndex index = new LicenseIndex(Map.of(coordinate, licenses), List.of());
        return evaluator.evaluate(List.of(component()), index, policy);
    }

    private LicensePolicyEvaluation detailed(
            SbomLicense license,
            LicensePolicySettings policy,
            SbomComponent component) {
        String coordinate = component.group() + ":" + component.name() + ":" + component.version();
        LicenseIndex index = new LicenseIndex(Map.of(coordinate, List.of(license)), List.of());
        return evaluator.evaluateDetailed(List.of(component), index, policy);
    }

    private LicensePolicyFinding onlyFinding(SbomLicense license, LicensePolicySettings policy) {
        return onlyFinding(List.of(license), policy);
    }

    private LicensePolicyFinding onlyFinding(List<SbomLicense> licenses, LicensePolicySettings policy) {
        List<LicensePolicyFinding> findings = findings(licenses, policy);
        assertEquals(1, findings.size(), findings.toString());
        return findings.getFirst();
    }

    private static SbomComponent component() {
        return component("org.example", "lib", "1.0.0");
    }

    private static SbomComponent component(String group, String artifact, String version) {
        String purl = PurlWriter.purl(group, artifact, version, "jar", Optional.empty());
        return new SbomComponent(
                SbomComponentType.LIBRARY, purl, group, artifact, version, purl,
                SbomComponentScope.REQUIRED, List.of(), List.of());
    }

    private static SbomLicense expression(String source) {
        return SbomLicense.expression(
                new SpdxExpressionParser().parse(source), Optional.of(source), Optional.empty());
    }

    private static LicensePolicySettings exceptionPolicy(
            List<String> allow,
            List<String> deny,
            Optional<String> version,
            List<String> exceptionAllow) {
        LicensePolicyException exception = new LicensePolicyException(
                "org.example:lib", exceptionAllow, version, "Reviewed dependency");
        return new LicensePolicySettings(
                allow, deny, UnknownLicensePolicy.WARN, Map.of(exception.dependency(), exception));
    }

    private static SbomLicense spdx(String id) {
        return SbomLicense.spdx(id);
    }

    private static SbomLicense unmapped(String name) {
        return SbomLicense.unmapped(Optional.of(name), Optional.empty());
    }

    private static SbomLicense unknown() {
        return SbomLicense.unknown();
    }
}
