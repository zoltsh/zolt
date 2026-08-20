package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;

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

    Optional<AuthoredDependencies> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        boolean present = false;
        ArrayList<AuthoredDependency> declarations = new ArrayList<>();
        AuthoredDependencies decoded = AuthoredDependencies.empty();
        for (Lane lane : LANES) {
            List<ManifestDecodeIndex.Entry> laneEntries = index.entries(lane.field());
            present |= !laneEntries.isEmpty() || index.section(lane.section())
                    .map(ValidatedManifestSection::source)
                    .filter(ManifestShapeSource::authoredTable)
                    .isPresent();
            for (ManifestDecodeIndex.Entry entry : laneEntries) {
                declarations.add(entries.decode(lane.lane(), entry));
                decoded = ManifestSemanticDiagnostics.construct(
                        entry.field(), () -> new AuthoredDependencies(declarations));
            }
        }
        return present ? Optional.of(decoded) : Optional.empty();
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
