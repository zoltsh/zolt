package sh.zolt.manifest.authored;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyDenyEntry;
import sh.zolt.manifest.ManifestModelValues;

/**
 * Authored dependency policy, including the separate exact-coordinate license-exception namespace.
 *
 * <p>This source model validates local structure only. Deny-wins evaluation and exception lifecycle
 * auditing ({@code used}, version-mismatched, missing, or redundant) require an effective resolved
 * closure and are deliberately deferred to effective policy construction.
 */
public record AuthoredDependencyPolicy(
        Optional<DependencyConflictPolicy> conflicts,
        List<DependencyDenyEntry> deny,
        Optional<AuthoredLicensePolicy> licenses,
        Map<DependencyCoordinate, AuthoredLicenseException> licenseExceptions) {
    public AuthoredDependencyPolicy {
        conflicts = Objects.requireNonNull(conflicts, "Authored dependency conflict policy must not be null.");
        deny = immutableDenyEntries(deny);
        licenses = Objects.requireNonNull(licenses, "Authored dependency license policy must not be null.");
        licenseExceptions = ManifestModelValues.immutableSortedMap(
                licenseExceptions,
                DependencyCoordinate::compareTo,
                "License exception coordinate",
                "License exception");
        if (!licenseExceptions.isEmpty()
                && (licenses.isEmpty() || licenses.orElseThrow().allow().isEmpty())) {
            throw new IllegalArgumentException(
                    "License exceptions require a non-empty global license allow list.");
        }
        if (conflicts.isEmpty() && deny.isEmpty() && licenses.isEmpty() && licenseExceptions.isEmpty()) {
            throw new IllegalArgumentException("Authored dependency policy must not be empty.");
        }
    }

    private static List<DependencyDenyEntry> immutableDenyEntries(List<DependencyDenyEntry> values) {
        List<DependencyDenyEntry> copy = ManifestModelValues.immutableList(values, "Dependency deny entries");
        Set<DependencyCoordinate> seen = new HashSet<>();
        for (DependencyDenyEntry entry : copy) {
            if (!seen.add(entry.coordinate())) {
                throw new IllegalArgumentException(
                        "Dependency deny entries must not repeat coordinate `" + entry.coordinate() + "`.");
            }
        }
        ArrayList<DependencyDenyEntry> sorted = new ArrayList<>(copy);
        sorted.sort((left, right) -> left.coordinate().compareTo(right.coordinate()));
        return Collections.unmodifiableList(sorted);
    }
}
