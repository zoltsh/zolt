package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyDenyEntry;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicenseException;
import sh.zolt.manifest.authored.AuthoredLicensePolicy;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Decodes authored dependency conflict, deny, and license-policy controls. */
final class ManifestDependencyPolicyDecoder {
    private final ManifestLicensePolicyDecoder licenses =
            new ManifestLicensePolicyDecoder();
    private final ManifestLicenseExceptionsDecoder exceptions =
            new ManifestLicenseExceptionsDecoder();

    Optional<AuthoredDependencyPolicy> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> conflictsField = index.field(
                FinalManifestDependencyFields.DEPENDENCY_POLICY_CONFLICTS);
        Optional<ValidatedManifestField> denyField = index.field(
                FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY);
        Optional<DependencyConflictPolicy> conflicts = conflictsField.map(
                ManifestDependencyPolicyDecoder::conflicts);
        List<DependencyDenyEntry> deny = denyField
                .map(ManifestDependencyPolicyDecoder::deny)
                .orElseGet(List::of);
        Optional<AuthoredLicensePolicy> licensePolicy = licenses.decode(index);
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
            ValidatedManifestField field) {
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
        }
        return List.copyOf(entries);
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
