package sh.zolt.sbom;

/** The outcome of evaluating a dependency (or one of its licenses) against the license policy. */
public enum LicenseVerdict {
    PERMITTED,
    PERMITTED_BY_EXCEPTION,
    WARN,
    VIOLATION
}
