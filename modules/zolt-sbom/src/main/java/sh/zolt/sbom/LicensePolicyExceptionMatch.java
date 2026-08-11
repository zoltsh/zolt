package sh.zolt.sbom;

/** The reviewed scoped exception that supplied one decisive policy term. */
public record LicensePolicyExceptionMatch(
        String dependency,
        String matchedVersion,
        String reason) {
}
