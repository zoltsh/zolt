package sh.zolt.manifest.authored;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LicensePolicyTerm;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.project.UnknownLicensePolicy;

/** Explicitly authored global fields in {@code [dependencies.policy.licenses]}. */
public record AuthoredLicensePolicy(
        List<LicensePolicyTerm> allow,
        List<LicensePolicyTerm> deny,
        Optional<UnknownLicensePolicy> unknown) {
    public AuthoredLicensePolicy {
        allow = ManifestModelValues.sortedDistinctList(allow, "Global license allow terms");
        deny = ManifestModelValues.sortedDistinctList(deny, "Global license deny terms");
        unknown = Objects.requireNonNull(unknown, "Authored unknown-license policy must not be null.");
        if (allow.isEmpty() && deny.isEmpty() && unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "An authored [dependencies.policy.licenses] table must contain a meaningful field.");
        }
    }
}
