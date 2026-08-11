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
        Optional<LicensePolicyExceptionMatch> exceptionMatch) {
    public LicensePolicyFinding(
            String coordinate,
            String purl,
            String license,
            LicenseVerdict verdict,
            String reason) {
        this(coordinate, purl, license, license, verdict, reason, Optional.empty());
    }

    public LicensePolicyFinding {
        exceptionMatch = exceptionMatch == null ? Optional.empty() : exceptionMatch;
    }
}
