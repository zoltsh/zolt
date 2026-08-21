package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
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

    Decoded decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<AuthoredDependencies> decodedDependencies =
                dependencies.decode(index, ignored -> {});
        Optional<AuthoredDependencyConstraints> decodedConstraints =
                constraints.decode(index, ignored -> {});
        Optional<AuthoredDependencyPolicy> decodedPolicy =
                policy.decode(index, ignored -> {});
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
