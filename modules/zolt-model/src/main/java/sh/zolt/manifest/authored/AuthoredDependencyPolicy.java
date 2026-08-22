package sh.zolt.manifest.authored;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.license.SpdxExpression;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyDenyEntry;
import sh.zolt.manifest.LicensePolicyTerm;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.SpdxLicenseTerm;

/**
 * Authored dependency policy, including the separate exact-coordinate license-exception namespace.
 *
 * <p>This source model validates everything decidable from the manifest alone, including the
 * contradiction of scoping an allowance of a globally denied license. Exception lifecycle auditing
 * ({@code used}, version-mismatched, missing, or redundant) and snapshot availability require an
 * effective resolved closure and stay deferred to effective policy construction.
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
        rejectDeniedScopedAllowances(licenses, licenseExceptions);
        if (conflicts.isEmpty() && deny.isEmpty() && licenses.isEmpty() && licenseExceptions.isEmpty()) {
            throw new IllegalArgumentException("Authored dependency policy must not be empty.");
        }
    }

    private static void rejectDeniedScopedAllowances(
            Optional<AuthoredLicensePolicy> licenses,
            Map<DependencyCoordinate, AuthoredLicenseException> licenseExceptions) {
        licenseExceptions.forEach((coordinate, exception) -> exception.allow()
                .forEach(allowed -> requireAllowableScopedTerm(licenses, coordinate, allowed)));
    }

    /**
     * Rejects a scoped allowance of a license the global policy denies (design §9.11).
     *
     * <p>Global deny cannot be overridden, and denying a base license denies every
     * {@code LICENSE WITH EXCEPTION} form of it, so such an exception is a contradiction in the
     * manifest rather than a runtime outcome. The final model boundary calls this for every scoped
     * term; the decoder calls it per authored item so the diagnostic anchors to the exact scoped
     * allow entry. Deny-wins evaluation stays in place as defense in depth.
     */
    public static void requireAllowableScopedTerm(
            Optional<AuthoredLicensePolicy> licenses,
            DependencyCoordinate coordinate,
            SpdxLicenseTerm allowed) {
        Objects.requireNonNull(licenses, "Authored dependency license policy must not be null.");
        Objects.requireNonNull(coordinate, "License exception coordinate must not be null.");
        Objects.requireNonNull(allowed, "License exception allow term must not be null.");
        if (licenses.isEmpty()) {
            return;
        }
        Set<String> denied = new HashSet<>();
        for (LicensePolicyTerm term : licenses.orElseThrow().deny()) {
            denied.add(term.value());
        }
        deniedBy(denied, allowed).ifPresent(denial -> {
            throw new IllegalArgumentException(
                    "License exception [dependencies.license-exceptions.\"" + coordinate
                            + "\"].allow term `" + allowed.value()
                            + "` is denied by [dependencies.policy.licenses].deny `" + denial
                            + "`; a scoped exception cannot override a global deny.");
        });
    }

    /** The denied term that forbids {@code allowed}, matching the runtime deny rule exactly. */
    private static Optional<String> deniedBy(Set<String> denied, SpdxLicenseTerm allowed) {
        if (denied.contains(allowed.value())) {
            return Optional.of(allowed.value());
        }
        if (allowed.expression() instanceof SpdxExpression.With with
                && denied.contains(with.licenseId())) {
            return Optional.of(with.licenseId());
        }
        return Optional.empty();
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
