package sh.zolt.sbom;

import java.util.List;

/** Complete policy result: component decisions plus configured-exception lifecycle audits. */
public record LicensePolicyEvaluation(
        List<LicensePolicyFinding> findings,
        List<LicenseExceptionAudit> exceptionAudits,
        int evaluated) {
    public LicensePolicyEvaluation {
        findings = findings == null ? List.of() : List.copyOf(findings);
        exceptionAudits = exceptionAudits == null ? List.of() : List.copyOf(exceptionAudits);
    }
}
