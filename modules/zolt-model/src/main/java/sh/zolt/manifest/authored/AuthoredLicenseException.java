package sh.zolt.manifest.authored;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.SpdxLicenseTerm;
import sh.zolt.project.VersionPolicy;

/** One reviewed exact-coordinate allowance from {@code [dependencies.license-exceptions]}. */
public record AuthoredLicenseException(
        List<SpdxLicenseTerm> allow,
        Optional<String> version,
        String reason) {
    public AuthoredLicenseException {
        allow = ManifestModelValues.sortedDistinctList(allow, "License exception allow terms");
        if (allow.isEmpty()) {
            throw new IllegalArgumentException("License exception allow terms must not be empty.");
        }
        version = Objects.requireNonNull(version, "License exception version must not be null.");
        version.ifPresent(AuthoredLicenseException::validateVersion);
        ManifestModelValues.requireNonBlank(reason, "License exception reason");
    }

    private static void validateVersion(String value) {
        VersionPolicy.violation(VersionPolicy.Context.EXTERNAL_DEPENDENCY, value, true)
                .ifPresent(violation -> {
                    throw new IllegalArgumentException(
                            "Invalid license exception version `" + value + "`: "
                                    + violation.guidance());
                });
    }
}
