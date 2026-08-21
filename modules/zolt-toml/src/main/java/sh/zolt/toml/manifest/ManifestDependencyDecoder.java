package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.SpdxLicenseTerm;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicenseException;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;

/** Coordinates authored dependency domains without resolving or composing them. */
final class ManifestDependencyDecoder {
    private final ManifestDependenciesDecoder dependencies =
            new ManifestDependenciesDecoder();
    private final ManifestDependencyConstraintsDecoder constraints =
            new ManifestDependencyConstraintsDecoder();
    private final ManifestDependencyPolicyDecoder policy =
            new ManifestDependencyPolicyDecoder();

    Decoded decode(
            ManifestDecodeIndex index,
            DependencyPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored dependency presence observer is required.");
        Optional<AuthoredDependencies> decodedDependencies = dependencies.decode(
                index,
                partial -> observer.present(new Decoded(
                        Optional.of(partial), Optional.empty(), Optional.empty())));
        Optional<AuthoredDependencyConstraints> decodedConstraints = constraints.decode(
                index,
                partial -> observer.present(new Decoded(
                        decodedDependencies, Optional.of(partial), Optional.empty())));
        Optional<AuthoredDependencyPolicy> decodedPolicy = policy.decode(
                index,
                partial -> observer.present(new Decoded(
                        decodedDependencies, decodedConstraints, Optional.of(partial))));
        return new Decoded(decodedDependencies, decodedConstraints, decodedPolicy);
    }

    record Decoded(
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredDependencyConstraints> constraints,
            Optional<AuthoredDependencyPolicy> policy) {
        Decoded {
            dependencies = Objects.requireNonNull(
                    dependencies, "Decoded dependencies must not be null.");
            constraints = Objects.requireNonNull(
                    constraints, "Decoded dependency constraints must not be null.");
            policy = Objects.requireNonNull(
                    policy, "Decoded dependency policy must not be null.");
        }
    }

    @FunctionalInterface
    interface DependencyPresenceObserver {
        void present(Decoded dependencies);
    }
}

/** Decodes the eight authored dependency lanes without resolving selectors. */
final class ManifestDependenciesDecoder {
    private static final List<Lane> LANES = List.of(
            new Lane(
                    FinalManifestPaths.DEPENDENCIES,
                    FinalManifestDependencyFields.DEPENDENCIES_ENTRY,
                    DependencyLane.IMPLEMENTATION),
            new Lane(
                    FinalManifestPaths.DEPENDENCIES_API,
                    FinalManifestDependencyFields.DEPENDENCIES_API_ENTRY,
                    DependencyLane.API),
            new Lane(
                    FinalManifestPaths.DEPENDENCIES_RUNTIME,
                    FinalManifestDependencyFields.DEPENDENCIES_RUNTIME_ENTRY,
                    DependencyLane.RUNTIME),
            new Lane(
                    FinalManifestPaths.DEPENDENCIES_PROVIDED,
                    FinalManifestDependencyFields.DEPENDENCIES_PROVIDED_ENTRY,
                    DependencyLane.PROVIDED),
            new Lane(
                    FinalManifestPaths.DEPENDENCIES_DEV,
                    FinalManifestDependencyFields.DEPENDENCIES_DEV_ENTRY,
                    DependencyLane.DEV),
            new Lane(
                    FinalManifestPaths.DEPENDENCIES_TEST,
                    FinalManifestDependencyFields.DEPENDENCIES_TEST_ENTRY,
                    DependencyLane.TEST),
            new Lane(
                    FinalManifestPaths.DEPENDENCIES_PROCESSOR,
                    FinalManifestDependencyFields.DEPENDENCIES_PROCESSOR_ENTRY,
                    DependencyLane.PROCESSOR),
            new Lane(
                    FinalManifestPaths.DEPENDENCIES_TEST_PROCESSOR,
                    FinalManifestDependencyFields.DEPENDENCIES_TEST_PROCESSOR_ENTRY,
                    DependencyLane.TEST_PROCESSOR));

    private final ManifestDependencyEntryDecoder entries =
            new ManifestDependencyEntryDecoder();

    Optional<AuthoredDependencies> decode(
            ManifestDecodeIndex index,
            DependenciesPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored dependencies presence observer is required.");
        boolean present = false;
        ArrayList<AuthoredDependency> declarations = new ArrayList<>();
        AuthoredDependencies decoded = AuthoredDependencies.empty();
        for (Lane lane : LANES) {
            List<ManifestDecodeIndex.Entry> laneEntries = index.entries(lane.field());
            Optional<ValidatedManifestSection> section = index.section(lane.section());
            boolean lanePresent = !laneEntries.isEmpty() || section
                    .map(ValidatedManifestSection::source)
                    .filter(ManifestShapeSource::authoredTable)
                    .isPresent();
            if (!present && lanePresent) {
                AuthoredDependencies observed = decoded;
                decoded = ManifestSemanticDiagnostics.construct(
                        section.orElseThrow(() -> new IllegalStateException(
                                "Authored dependency lane has no retained section evidence.")),
                        () -> {
                            observer.present(observed);
                            return observed;
                        });
            }
            present |= lanePresent;
            for (ManifestDecodeIndex.Entry entry : laneEntries) {
                declarations.add(entries.decode(lane.lane(), entry));
                decoded = ManifestSemanticDiagnostics.construct(
                        entry.field(), () -> new AuthoredDependencies(declarations));
            }
        }
        return present ? Optional.of(decoded) : Optional.empty();
    }

    @FunctionalInterface
    interface DependenciesPresenceObserver {
        void present(AuthoredDependencies dependencies);
    }

    private record Lane(
            ManifestPath section,
            ManifestField field,
            DependencyLane lane) {
        private Lane {
            Objects.requireNonNull(section, "Dependency lane section is required.");
            Objects.requireNonNull(field, "Dependency lane field is required.");
            Objects.requireNonNull(lane, "Dependency lane is required.");
        }
    }
}

/** Decodes optional strict dependency constraints without resolving aliases. */
final class ManifestDependencyConstraintsDecoder {
    Optional<AuthoredDependencyConstraints> decode(
            ManifestDecodeIndex index,
            ConstraintsPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(
                observer, "Authored dependency constraints presence observer is required.");
        Optional<ValidatedManifestSection> section = index.section(
                FinalManifestPaths.DEPENDENCY_CONSTRAINTS)
                .filter(candidate -> candidate.source().authoredTable());
        if (section.isEmpty()) {
            return Optional.empty();
        }
        AuthoredDependencyConstraints empty = AuthoredDependencyConstraints.empty();
        ManifestSemanticDiagnostics.construct(section.orElseThrow(), () -> {
            observer.present(empty);
            return empty;
        });

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

    @FunctionalInterface
    interface ConstraintsPresenceObserver {
        void present(AuthoredDependencyConstraints constraints);
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

/** Decodes exact-coordinate license exceptions without evaluating resolved evidence. */
final class ManifestLicenseExceptionsDecoder {
    Map<DependencyCoordinate, AuthoredLicenseException> decode(
            ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        LinkedHashMap<DependencyCoordinate, AuthoredLicenseException> exceptions =
                new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry :
                index.sectionEntries(FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTION)) {
            DependencyCoordinate coordinate = ManifestSemanticDiagnostics.construct(
                    entry.section(), () -> new DependencyCoordinate(entry.key()));
            AuthoredLicenseException exception = exception(index, entry);
            if (exceptions.put(coordinate, exception) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate license exception `"
                                + coordinate + "`.");
            }
        }
        return Collections.unmodifiableMap(exceptions);
    }

    private static AuthoredLicenseException exception(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        ValidatedManifestField allowField = ManifestSemanticDiagnostics.requiredField(
                index,
                entry,
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_ALLOW);
        List<SpdxLicenseTerm> allow = terms(allowField);
        ManifestSemanticDiagnostics.construct(allowField, () -> requireNonEmpty(allow));

        ValidatedManifestField reasonField = ManifestSemanticDiagnostics.requiredField(
                index,
                entry,
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_REASON);
        String reason = ManifestTomlValues.string(reasonField);
        AuthoredLicenseException exception = ManifestSemanticDiagnostics.construct(
                reasonField,
                () -> new AuthoredLicenseException(allow, Optional.empty(), reason));
        return index.field(
                        entry,
                        FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_VERSION)
                .map(field -> ManifestSemanticDiagnostics.construct(
                        field,
                        () -> new AuthoredLicenseException(
                                allow,
                                Optional.of(ManifestTomlValues.string(field)),
                                reason)))
                .orElse(exception);
    }

    private static List<SpdxLicenseTerm> terms(ValidatedManifestField field) {
        List<String> values = ManifestTomlValues.strings(field);
        ArrayList<SpdxLicenseTerm> terms = new ArrayList<>(values.size());
        Set<SpdxLicenseTerm> seen = new LinkedHashSet<>();
        for (int item = 0; item < values.size(); item++) {
            String value = values.get(item);
            SpdxLicenseTerm term = ManifestSemanticDiagnostics.construct(
                    field, item, () -> new SpdxLicenseTerm(value));
            ManifestSemanticDiagnostics.construct(
                    field, item, () -> requireUnique(seen, term));
            terms.add(term);
        }
        return List.copyOf(terms);
    }

    private static List<SpdxLicenseTerm> requireNonEmpty(
            List<SpdxLicenseTerm> terms) {
        if (terms.isEmpty()) {
            throw new IllegalArgumentException(
                    "License exception allow terms must not be empty.");
        }
        return terms;
    }

    private static SpdxLicenseTerm requireUnique(
            Set<SpdxLicenseTerm> seen,
            SpdxLicenseTerm term) {
        if (!seen.add(term)) {
            throw new IllegalArgumentException(
                    "License exception allow term `" + term
                            + "` is declared more than once.");
        }
        return term;
    }
}
