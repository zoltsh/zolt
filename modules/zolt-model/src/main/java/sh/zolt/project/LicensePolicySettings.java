package sh.zolt.project;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The {@code [dependencyPolicy.licenses]} gate configuration.
 *
 * <p>Precedence rule: a license is permitted iff its id is not in {@code deny} AND ({@code allow} is
 * empty OR its id is in {@code allow}) — deny always wins, and a non-empty allow-list is
 * authoritative. {@code unknown} controls the treatment of UNKNOWN-license dependencies. Scoped
 * exceptions may extend a non-empty allow list for one exact dependency, but never override deny.
 */
public record LicensePolicySettings(
        List<String> allow,
        List<String> deny,
        UnknownLicensePolicy unknown,
        Map<String, LicensePolicyException> exceptions) {
    public LicensePolicySettings(List<String> allow, List<String> deny, UnknownLicensePolicy unknown) {
        this(allow, deny, unknown, Map.of());
    }

    public LicensePolicySettings {
        allow = allow == null ? List.of() : List.copyOf(allow);
        deny = deny == null ? List.of() : List.copyOf(deny);
        unknown = unknown == null ? UnknownLicensePolicy.WARN : unknown;
        Map<String, LicensePolicyException> configured = exceptions == null ? Map.of() : exceptions;
        if (!configured.isEmpty() && allow.isEmpty()) {
            throw new IllegalArgumentException(
                    "License policy exceptions require a non-empty global allow list.");
        }
        Set<String> dependencies = new HashSet<>();
        TreeMap<String, LicensePolicyException> sorted = new TreeMap<>();
        for (Map.Entry<String, LicensePolicyException> entry : configured.entrySet()) {
            LicensePolicyException exception = entry.getValue();
            if (exception == null) {
                throw new IllegalArgumentException("License policy exception values must not be null.");
            }
            if (!dependencies.add(exception.dependency())) {
                throw new IllegalArgumentException(
                        "Duplicate license policy exception dependency `" + exception.dependency() + "`.");
            }
            if (!exception.dependency().equals(entry.getKey())) {
                throw new IllegalArgumentException("License policy exception map key `"
                        + entry.getKey()
                        + "` must equal embedded dependency `"
                        + exception.dependency()
                        + "`.");
            }
            sorted.put(entry.getKey(), exception);
        }
        exceptions = Collections.unmodifiableMap(sorted);
    }

    public static LicensePolicySettings defaults() {
        return new LicensePolicySettings(List.of(), List.of(), UnknownLicensePolicy.WARN, Map.of());
    }

    /** True when nothing is configured — no allow/deny entries and the default unknown strictness. */
    public boolean isDefault() {
        return allow.isEmpty()
                && deny.isEmpty()
                && unknown == UnknownLicensePolicy.WARN
                && exceptions.isEmpty();
    }
}
