package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyDenyEntry;
import sh.zolt.manifest.LicensePolicyTerm;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicenseException;
import sh.zolt.manifest.authored.AuthoredLicensePolicy;
import sh.zolt.project.UnknownLicensePolicy;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Decodes authored dependency conflict, deny, and license-policy controls. */
final class ManifestDependencyPolicyDecoder {
    private final ManifestLicensePolicyDecoder licenses =
            new ManifestLicensePolicyDecoder();
    private final ManifestLicenseExceptionsDecoder exceptions =
            new ManifestLicenseExceptionsDecoder();

    Optional<AuthoredDependencyPolicy> decode(
            ManifestDecodeIndex index,
            PolicyPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored dependency policy presence observer is required.");
        Optional<ValidatedManifestField> conflictsField = index.field(
                FinalManifestDependencyFields.DEPENDENCY_POLICY_CONFLICTS);
        Optional<ValidatedManifestField> denyField = index.field(
                FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY);
        AtomicBoolean observed = new AtomicBoolean();
        Consumer<AuthoredDependencyPolicy> presence = policy -> {
            if (observed.compareAndSet(false, true)) {
                observer.present(policy);
            }
        };
        Optional<DependencyConflictPolicy> conflicts = conflictsField.map(
                ManifestDependencyPolicyDecoder::conflicts);
        if (conflictsField.isPresent()) {
            ManifestSemanticDiagnostics.construct(
                    conflictsField.orElseThrow(),
                    () -> {
                        AuthoredDependencyPolicy policy = new AuthoredDependencyPolicy(
                                conflicts, List.of(), Optional.empty(), Map.of());
                        presence.accept(policy);
                        return policy;
                    });
        }
        List<DependencyDenyEntry> deny = denyField
                .map(field -> deny(
                        field,
                        prefix -> presence.accept(new AuthoredDependencyPolicy(
                                conflicts, prefix, Optional.empty(), Map.of()))))
                .orElseGet(List::of);
        Optional<AuthoredLicensePolicy> licensePolicy = licenses.decode(
                index,
                partial -> presence.accept(new AuthoredDependencyPolicy(
                        conflicts, deny, Optional.of(partial), Map.of())));
        Map<DependencyCoordinate, AuthoredLicenseException> licenseExceptions =
                exceptions.decode(index);

        boolean authored = conflictsField.isPresent()
                || denyField.isPresent()
                || licensePolicy.isPresent()
                || !licenseExceptions.isEmpty();
        if (!authored) {
            return Optional.empty();
        }
        Supplier<AuthoredDependencyPolicy> factory = () -> new AuthoredDependencyPolicy(
                conflicts, deny, licensePolicy, licenseExceptions);
        if (!licenseExceptions.isEmpty()) {
            ValidatedManifestSection section = index.section(
                            FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTIONS)
                    .orElseThrow(() -> new IllegalStateException(
                            "Validated license exceptions have no collection evidence."));
            return Optional.of(ManifestSemanticDiagnostics.construct(section, factory));
        }
        if (conflicts.isEmpty()
                && denyField.isPresent()
                && deny.isEmpty()
                && licensePolicy.isEmpty()) {
            return Optional.of(ManifestSemanticDiagnostics.construct(
                    denyField.orElseThrow(), factory));
        }
        ValidatedManifestSection section = licensePolicy.isPresent()
                ? index.section(FinalManifestPaths.DEPENDENCY_LICENSE_POLICY).orElseThrow()
                : index.section(FinalManifestPaths.DEPENDENCY_POLICY).orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(section, factory));
    }

    private static DependencyConflictPolicy conflicts(
            ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        return ManifestSemanticDiagnostics.construct(
                field,
                () -> DependencyConflictPolicy.fromId(value).orElseThrow(() ->
                        new IllegalStateException(
                                "Final manifest schema/model drift for conflict policy `"
                                        + value + "`.")));
    }

    private static List<DependencyDenyEntry> deny(
            ValidatedManifestField field,
            Consumer<List<DependencyDenyEntry>> prefixObserver) {
        List<ManifestInlineTable> tables = ManifestTomlValues.inlineObjectArray(field);
        ArrayList<DependencyDenyEntry> entries = new ArrayList<>(tables.size());
        Set<DependencyCoordinate> seen = new LinkedHashSet<>();
        for (int item = 0; item < tables.size(); item++) {
            int index = item;
            ManifestInlineTable table = tables.get(item);
            String authoredCoordinate = table.requiredString(
                    FinalManifestObjectShapes.DENY_ENTRY_COORDINATE);
            DependencyCoordinate coordinate = ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.DENY_ENTRY_COORDINATE,
                    () -> new DependencyCoordinate(authoredCoordinate));
            Optional<String> reason = table.optionalString(
                    FinalManifestObjectShapes.DENY_ENTRY_REASON);
            DependencyDenyEntry entry = reason
                    .map(value -> ManifestSemanticDiagnostics.construct(
                            table,
                            FinalManifestObjectShapes.DENY_ENTRY_REASON,
                            () -> new DependencyDenyEntry(
                                    coordinate, Optional.of(value))))
                    .orElseGet(() -> ManifestSemanticDiagnostics.construct(
                            field,
                            index,
                            () -> new DependencyDenyEntry(
                                    coordinate, Optional.empty())));
            ManifestSemanticDiagnostics.construct(
                    field, index, () -> requireUniqueDeny(seen, coordinate));
            entries.add(entry);
            if (item == 0) {
                List<DependencyDenyEntry> prefix = List.copyOf(entries);
                ManifestSemanticDiagnostics.construct(field, index, () -> {
                    prefixObserver.accept(prefix);
                    return prefix;
                });
            }
        }
        return List.copyOf(entries);
    }

    @FunctionalInterface
    interface PolicyPresenceObserver {
        void present(AuthoredDependencyPolicy policy);
    }

    private static DependencyCoordinate requireUniqueDeny(
            Set<DependencyCoordinate> seen,
            DependencyCoordinate coordinate) {
        if (!seen.add(coordinate)) {
            throw new IllegalArgumentException(
                    "Dependency deny coordinate `" + coordinate
                            + "` is declared more than once.");
        }
        return coordinate;
    }
}

/** Decodes the optional global dependency-license policy. */
final class ManifestLicensePolicyDecoder {
    Optional<AuthoredLicensePolicy> decode(
            ManifestDecodeIndex index,
            LicensePolicyPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(
                observer, "Authored dependency license policy presence observer is required.");
        Optional<ValidatedManifestSection> section = index.section(
                FinalManifestPaths.DEPENDENCY_LICENSE_POLICY);
        Optional<ValidatedManifestField> allowField = index.field(
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_POLICY_ALLOW);
        Optional<ValidatedManifestField> denyField = index.field(
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_POLICY_DENY);
        Optional<ValidatedManifestField> unknownField = index.field(
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_POLICY_UNKNOWN);
        boolean authored = section.filter(candidate -> candidate.source().authoredTable()).isPresent()
                || allowField.isPresent()
                || denyField.isPresent()
                || unknownField.isPresent();
        if (!authored) {
            return Optional.empty();
        }

        AtomicBoolean observed = new AtomicBoolean();
        Consumer<AuthoredLicensePolicy> presence = policy -> {
            if (observed.compareAndSet(false, true)) {
                observer.present(policy);
            }
        };
        List<LicensePolicyTerm> allow = terms(
                allowField,
                "allow",
                prefix -> presence.accept(new AuthoredLicensePolicy(
                        prefix, List.of(), Optional.empty())));
        List<LicensePolicyTerm> deny = terms(
                denyField,
                "deny",
                prefix -> presence.accept(new AuthoredLicensePolicy(
                        allow, prefix, Optional.empty())));
        Optional<UnknownLicensePolicy> unknown = unknownField.map(
                ManifestLicensePolicyDecoder::unknown);
        if (unknownField.isPresent()) {
            ManifestSemanticDiagnostics.construct(
                    unknownField.orElseThrow(),
                    () -> {
                        AuthoredLicensePolicy policy =
                                new AuthoredLicensePolicy(allow, deny, unknown);
                        presence.accept(policy);
                        return policy;
                    });
        }
        Supplier<AuthoredLicensePolicy> factory = () ->
                new AuthoredLicensePolicy(allow, deny, unknown);
        if (allow.isEmpty() && deny.isEmpty() && unknown.isEmpty()) {
            Optional<ValidatedManifestField> causalField = denyField.or(() -> allowField);
            if (causalField.isPresent()) {
                return Optional.of(ManifestSemanticDiagnostics.construct(
                        causalField.orElseThrow(), factory));
            }
        }
        return Optional.of(ManifestSemanticDiagnostics.construct(
                section.orElseThrow(), factory));
    }

    private static List<LicensePolicyTerm> terms(
            Optional<ValidatedManifestField> field,
            String listName,
            Consumer<List<LicensePolicyTerm>> prefixObserver) {
        if (field.isEmpty()) {
            return List.of();
        }
        List<String> values = ManifestTomlValues.strings(field.orElseThrow());
        ArrayList<LicensePolicyTerm> terms = new ArrayList<>(values.size());
        Set<LicensePolicyTerm> seen = new LinkedHashSet<>();
        for (int item = 0; item < values.size(); item++) {
            String value = values.get(item);
            LicensePolicyTerm term = ManifestSemanticDiagnostics.construct(
                    field.orElseThrow(),
                    item,
                    () -> LicensePolicyTerm.fromAuthored(value));
            ManifestSemanticDiagnostics.construct(
                    field.orElseThrow(),
                    item,
                    () -> requireUnique(seen, term, listName));
            terms.add(term);
            if (item == 0) {
                List<LicensePolicyTerm> prefix = List.copyOf(terms);
                ManifestSemanticDiagnostics.construct(
                        field.orElseThrow(), item, () -> {
                            prefixObserver.accept(prefix);
                            return prefix;
                        });
            }
        }
        return List.copyOf(terms);
    }

    @FunctionalInterface
    interface LicensePolicyPresenceObserver {
        void present(AuthoredLicensePolicy policy);
    }

    private static LicensePolicyTerm requireUnique(
            Set<LicensePolicyTerm> seen,
            LicensePolicyTerm term,
            String listName) {
        if (!seen.add(term)) {
            throw new IllegalArgumentException(
                    "Global license " + listName + " term `" + term
                            + "` is declared more than once.");
        }
        return term;
    }

    private static UnknownLicensePolicy unknown(ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        return ManifestSemanticDiagnostics.construct(
                field,
                () -> UnknownLicensePolicy.fromConfigValue(value).orElseThrow(() ->
                        new IllegalStateException(
                                "Final manifest schema/model drift for unknown-license policy `"
                                        + value + "`.")));
    }
}
