package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Decodes optional strict dependency constraints without resolving aliases. */
final class ManifestDependencyConstraintsDecoder {
    Optional<AuthoredDependencyConstraints> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestSection> section = index.section(
                FinalManifestPaths.DEPENDENCY_CONSTRAINTS)
                .filter(candidate -> candidate.source().authoredTable());
        if (section.isEmpty()) {
            return Optional.empty();
        }

        var entries = index.entries(
                FinalManifestDependencyFields.DEPENDENCY_CONSTRAINTS_ENTRY);
        Map<DependencyCoordinate, AuthoredDependencyConstraint> constraints =
                new LinkedHashMap<>();
        for (ManifestDecodeIndex.Entry entry : entries) {
            ValidatedManifestField field = entry.field();
            DependencyCoordinate coordinate = ManifestSemanticDiagnostics.construct(
                    field, () -> new DependencyCoordinate(entry.key()));
            AuthoredDependencyConstraint constraint = constraint(field);
            if (constraints.put(coordinate, constraint) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate dependency constraint `"
                                + coordinate + "`.");
            }
        }
        return Optional.of(ManifestSemanticDiagnostics.construct(
                section.orElseThrow(),
                () -> new AuthoredDependencyConstraints(constraints)));
    }

    private static AuthoredDependencyConstraint constraint(
            ValidatedManifestField field) {
        if (ManifestTomlValues.isString(field)) {
            DependencyConstraintSelector selector = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new DependencyConstraintSelector.FixedVersion(
                            ManifestTomlValues.string(field)));
            return ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredDependencyConstraint(selector, Optional.empty()));
        }
        ManifestInlineTable table = ManifestTomlValues.inlineObject(field);
        DependencyConstraintSelector selector = selector(table);
        AuthoredDependencyConstraint constraint = ManifestSemanticDiagnostics.construct(
                field,
                () -> new AuthoredDependencyConstraint(selector, Optional.empty()));
        return table.optionalString(FinalManifestObjectShapes.CONSTRAINT_REASON)
                .map(reason -> ManifestSemanticDiagnostics.construct(
                        table,
                        FinalManifestObjectShapes.CONSTRAINT_REASON,
                        () -> new AuthoredDependencyConstraint(
                                selector, Optional.of(reason))))
                .orElse(constraint);
    }

    private static DependencyConstraintSelector selector(
            ManifestInlineTable table) {
        Optional<String> version = table.optionalString(
                FinalManifestObjectShapes.CONSTRAINT_VERSION);
        if (version.isPresent()) {
            return ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.CONSTRAINT_VERSION,
                    () -> new DependencyConstraintSelector.FixedVersion(
                            version.orElseThrow()));
        }
        String versionRef = table.requiredString(
                FinalManifestObjectShapes.CONSTRAINT_VERSION_REF);
        return ManifestSemanticDiagnostics.construct(
                table,
                FinalManifestObjectShapes.CONSTRAINT_VERSION_REF,
                () -> new DependencyConstraintSelector.VersionReference(
                        new LocalId(versionRef)));
    }
}
