package sh.zolt.sbom;

import java.util.Optional;

/** A reportable license-policy decision, including scoped-exception evidence when one was used. */
public record LicensePolicyFinding(
        String coordinate,
        String purl,
        String declaration,
        String license,
        LicenseVerdict verdict,
        String reason,
        Optional<LicensePolicyExceptionMatch> exceptionMatch,
        LicensePolicyFindingCause cause) {
    public LicensePolicyFinding(
            String coordinate,
            String purl,
            String license,
            LicenseVerdict verdict,
            String reason) {
        this(
                coordinate,
                purl,
                license,
                license,
                verdict,
                reason,
                Optional.empty(),
                compatibilityCause(verdict, reason));
    }

    public LicensePolicyFinding(
            String coordinate,
            String purl,
            String declaration,
            String license,
            LicenseVerdict verdict,
            String reason,
            Optional<LicensePolicyExceptionMatch> exceptionMatch) {
        this(
                coordinate,
                purl,
                declaration,
                license,
                verdict,
                reason,
                exceptionMatch,
                compatibilityCause(verdict, reason));
    }

    public LicensePolicyFinding {
        exceptionMatch = exceptionMatch == null ? Optional.empty() : exceptionMatch;
        cause = cause == null ? compatibilityCause(verdict, reason) : cause;
    }

    private static LicensePolicyFindingCause compatibilityCause(LicenseVerdict verdict, String reason) {
        if (verdict == LicenseVerdict.PERMITTED) {
            return LicensePolicyFindingCause.PERMITTED;
        }
        if (verdict == LicenseVerdict.PERMITTED_BY_EXCEPTION) {
            return LicensePolicyFindingCause.SCOPED_EXCEPTION;
        }
        if (reason != null && reason.contains(".deny")) {
            return LicensePolicyFindingCause.GLOBAL_DENY;
        }
        if (reason != null && reason.contains(".allow")) {
            return LicensePolicyFindingCause.ALLOW_LIST;
        }
        return LicensePolicyFindingCause.UNRECOGNIZED;
    }
}
