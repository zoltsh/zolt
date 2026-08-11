package sh.zolt.project;

import java.util.List;
import java.util.Optional;

/** One reviewed SPDX allowance scoped to an exact dependency coordinate and optional version. */
public record LicensePolicyException(
        String dependency,
        List<String> allow,
        Optional<String> version,
        String reason) {
    public LicensePolicyException {
        if (dependency == null || dependency.isBlank()) {
            throw new IllegalArgumentException("License policy exception dependency must not be blank.");
        }
        allow = allow == null ? List.of() : allow.stream().distinct().sorted().toList();
        version = version == null ? Optional.empty() : version;
        if (allow.isEmpty()) {
            throw new IllegalArgumentException("License policy exception allow list must not be empty.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("License policy exception reason must not be blank.");
        }
    }
}
