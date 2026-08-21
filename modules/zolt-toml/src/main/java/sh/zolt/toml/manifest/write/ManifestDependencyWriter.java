package sh.zolt.toml.manifest.write;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyDenyEntry;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LicensePolicyTerm;
import sh.zolt.manifest.SpdxLicenseTerm;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicenseException;
import sh.zolt.manifest.authored.AuthoredLicensePolicy;
import sh.zolt.manifest.authored.DependencyVariant;
import sh.zolt.project.UnknownLicensePolicy;
import sh.zolt.toml.schema.FinalManifestDependencyFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits canonical dependency lanes, strict constraints, and dependency policy. */
final class ManifestDependencyWriter {
    private static final List<Lane> LANES = List.of(
            lane(FinalManifestPaths.DEPENDENCIES,
                    FinalManifestDependencyFields.DEPENDENCIES_ENTRY,
                    DependencyLane.IMPLEMENTATION),
            lane(FinalManifestPaths.DEPENDENCIES_API,
                    FinalManifestDependencyFields.DEPENDENCIES_API_ENTRY,
                    DependencyLane.API),
            lane(FinalManifestPaths.DEPENDENCIES_RUNTIME,
                    FinalManifestDependencyFields.DEPENDENCIES_RUNTIME_ENTRY,
                    DependencyLane.RUNTIME),
            lane(FinalManifestPaths.DEPENDENCIES_PROVIDED,
                    FinalManifestDependencyFields.DEPENDENCIES_PROVIDED_ENTRY,
                    DependencyLane.PROVIDED),
            lane(FinalManifestPaths.DEPENDENCIES_DEV,
                    FinalManifestDependencyFields.DEPENDENCIES_DEV_ENTRY,
                    DependencyLane.DEV),
            lane(FinalManifestPaths.DEPENDENCIES_TEST,
                    FinalManifestDependencyFields.DEPENDENCIES_TEST_ENTRY,
                    DependencyLane.TEST),
            lane(FinalManifestPaths.DEPENDENCIES_PROCESSOR,
                    FinalManifestDependencyFields.DEPENDENCIES_PROCESSOR_ENTRY,
                    DependencyLane.PROCESSOR),
            lane(FinalManifestPaths.DEPENDENCIES_TEST_PROCESSOR,
                    FinalManifestDependencyFields.DEPENDENCIES_TEST_PROCESSOR_ENTRY,
                    DependencyLane.TEST_PROCESSOR));
    private static final ManifestSection CONSTRAINTS = section(
            FinalManifestPaths.DEPENDENCY_CONSTRAINTS);
    private static final ManifestSection POLICY = section(
            FinalManifestPaths.DEPENDENCY_POLICY);
    private static final ManifestSection LICENSE_POLICY = section(
            FinalManifestPaths.DEPENDENCY_LICENSE_POLICY);
    private static final ManifestSection LICENSE_EXCEPTION = section(
            FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTION);

    void write(
            ManifestTomlEmitter emitter,
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredDependencyConstraints> constraints,
            Optional<AuthoredDependencyPolicy> policy) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        Objects.requireNonNull(dependencies, "Authored dependencies are required.")
                .filter(value -> !value.declarations().isEmpty())
                .ifPresent(value -> writeDependencies(emitter, value));
        Objects.requireNonNull(constraints, "Authored dependency constraints are required.")
                .filter(value -> !value.entries().isEmpty())
                .ifPresent(value -> writeConstraints(emitter, value));
        Objects.requireNonNull(policy, "Authored dependency policy is required.")
                .ifPresent(value -> writePolicy(emitter, value));
    }

    private static void writeDependencies(
            ManifestTomlEmitter emitter, AuthoredDependencies dependencies) {
        for (Lane lane : LANES) {
            List<AuthoredDependency> entries = dependencies.inLane(lane.lane()).stream()
                    .sorted(Comparator.comparing(AuthoredDependency::variant))
                    .toList();
            if (!entries.isEmpty()) {
                emitter.section(lane.section());
            }
            for (AuthoredDependency dependency : entries) {
                emitter.dynamicField(
                        lane.field(), dependency.coordinate().value(), dependency(dependency));
            }
        }
    }

    private static String dependency(AuthoredDependency dependency) {
        AuthoredDependencyMetadata metadata = dependency.metadata();
        boolean canonicalMetadata = metadata.optional()
                || metadata.publishOnly()
                || metadata.classifier().isPresent()
                || metadata.type().filter(value -> !DependencyVariant.DEFAULT_TYPE.equals(value)).isPresent()
                || !metadata.exclusions().isEmpty();
        if (dependency.selector() instanceof DependencySelector.FixedVersion fixed
                && !canonicalMetadata) {
            return string(fixed.value());
        }

        ArrayList<ManifestTomlValueEncoder.InlineMember> members = new ArrayList<>();
        addDependencySelector(members, dependency.selector());
        if (metadata.optional()) {
            members.add(booleanMember(FinalManifestObjectShapes.DEPENDENCY_OPTIONAL.name()));
        }
        if (metadata.publishOnly()) {
            members.add(booleanMember(FinalManifestObjectShapes.DEPENDENCY_PUBLISH_ONLY.name()));
        }
        metadata.classifier().ifPresent(value -> members.add(member(
                FinalManifestObjectShapes.DEPENDENCY_CLASSIFIER.name(), value)));
        metadata.type()
                .filter(value -> !DependencyVariant.DEFAULT_TYPE.equals(value))
                .ifPresent(value -> members.add(member(
                        FinalManifestObjectShapes.DEPENDENCY_TYPE.name(), value)));
        if (!metadata.exclusions().isEmpty()) {
            members.add(ManifestTomlValueEncoder.member(
                    FinalManifestObjectShapes.DEPENDENCY_EXCLUDE.name(),
                    coordinateArray(metadata.exclusions())));
        }
        return ManifestTomlValueEncoder.inlineObject(members);
    }

    private static void addDependencySelector(
            List<ManifestTomlValueEncoder.InlineMember> members,
            DependencySelector selector) {
        switch (selector) {
            case DependencySelector.FixedVersion fixed -> members.add(member(
                    FinalManifestObjectShapes.DEPENDENCY_VERSION.name(), fixed.value()));
            case DependencySelector.VersionReference reference -> members.add(member(
                    FinalManifestObjectShapes.DEPENDENCY_VERSION_REF.name(),
                    reference.alias().value()));
            case DependencySelector.Managed ignored -> members.add(booleanMember(
                    FinalManifestObjectShapes.DEPENDENCY_MANAGED.name()));
            case DependencySelector.Workspace ignored -> members.add(booleanMember(
                    FinalManifestObjectShapes.DEPENDENCY_WORKSPACE.name()));
        }
    }

    private static void writeConstraints(
            ManifestTomlEmitter emitter, AuthoredDependencyConstraints constraints) {
        emitter.section(CONSTRAINTS);
        for (Map.Entry<DependencyCoordinate, AuthoredDependencyConstraint> entry
                : constraints.entries().entrySet()) {
            emitter.dynamicField(
                    FinalManifestDependencyFields.DEPENDENCY_CONSTRAINTS_ENTRY,
                    entry.getKey().value(),
                    constraint(entry.getValue()));
        }
    }

    private static String constraint(AuthoredDependencyConstraint constraint) {
        if (constraint.selector() instanceof DependencyConstraintSelector.FixedVersion fixed
                && constraint.reason().isEmpty()) {
            return string(fixed.value());
        }
        ArrayList<ManifestTomlValueEncoder.InlineMember> members = new ArrayList<>();
        switch (constraint.selector()) {
            case DependencyConstraintSelector.FixedVersion fixed -> members.add(member(
                    FinalManifestObjectShapes.CONSTRAINT_VERSION.name(), fixed.value()));
            case DependencyConstraintSelector.VersionReference reference -> members.add(member(
                    FinalManifestObjectShapes.CONSTRAINT_VERSION_REF.name(),
                    reference.alias().value()));
        }
        constraint.reason().ifPresent(value -> members.add(member(
                FinalManifestObjectShapes.CONSTRAINT_REASON.name(), value)));
        return ManifestTomlValueEncoder.inlineObject(members);
    }

    private static void writePolicy(
            ManifestTomlEmitter emitter, AuthoredDependencyPolicy policy) {
        emitter.section(POLICY);
        policy.conflicts()
                .filter(value -> !value.equals(sh.zolt.manifest.DependencyConflictPolicy.RESOLVE))
                .ifPresent(value -> emitter.field(
                        FinalManifestDependencyFields.DEPENDENCY_POLICY_CONFLICTS,
                        string(value.id())));
        if (!policy.deny().isEmpty()) {
            emitter.field(
                    FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY,
                    ManifestTomlValueEncoder.fieldArray(
                            FinalManifestDependencyFields.DEPENDENCY_POLICY_DENY,
                            policy.deny().stream()
                            .map(ManifestDependencyWriter::denyEntry)
                            .toList()));
        }
        policy.licenses().ifPresent(value -> writeLicensePolicy(emitter, value));
        for (Map.Entry<DependencyCoordinate, AuthoredLicenseException> entry
                : policy.licenseExceptions().entrySet()) {
            writeLicenseException(emitter, entry.getKey(), entry.getValue());
        }
    }

    private static String denyEntry(DependencyDenyEntry entry) {
        ArrayList<ManifestTomlValueEncoder.InlineMember> members = new ArrayList<>();
        members.add(member(
                FinalManifestObjectShapes.DENY_ENTRY_COORDINATE.name(),
                entry.coordinate().value()));
        entry.reason().ifPresent(value -> members.add(member(
                FinalManifestObjectShapes.DENY_ENTRY_REASON.name(), value)));
        return ManifestTomlValueEncoder.inlineObject(members);
    }

    private static void writeLicensePolicy(
            ManifestTomlEmitter emitter, AuthoredLicensePolicy policy) {
        emitter.section(LICENSE_POLICY);
        if (!policy.allow().isEmpty()) {
            emitter.field(
                    FinalManifestDependencyFields.DEPENDENCY_LICENSE_POLICY_ALLOW,
                    licenseTerms(
                            FinalManifestDependencyFields.DEPENDENCY_LICENSE_POLICY_ALLOW,
                            policy.allow()));
        }
        if (!policy.deny().isEmpty()) {
            emitter.field(
                    FinalManifestDependencyFields.DEPENDENCY_LICENSE_POLICY_DENY,
                    licenseTerms(
                            FinalManifestDependencyFields.DEPENDENCY_LICENSE_POLICY_DENY,
                            policy.deny()));
        }
        policy.unknown()
                .filter(value -> value != UnknownLicensePolicy.WARN)
                .ifPresent(value -> emitter.field(
                        FinalManifestDependencyFields.DEPENDENCY_LICENSE_POLICY_UNKNOWN,
                        string(value.configValue())));
    }

    private static void writeLicenseException(
            ManifestTomlEmitter emitter,
            DependencyCoordinate coordinate,
            AuthoredLicenseException exception) {
        emitter.namedSection(LICENSE_EXCEPTION, coordinate.value());
        emitter.field(
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_ALLOW,
                spdxTerms(
                        FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_ALLOW,
                        exception.allow()));
        exception.version().ifPresent(value -> emitter.field(
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_VERSION,
                string(value)));
        emitter.field(
                FinalManifestDependencyFields.DEPENDENCY_LICENSE_EXCEPTION_REASON,
                string(exception.reason()));
    }

    private static String licenseTerms(ManifestField field, List<LicensePolicyTerm> terms) {
        return ManifestTomlValueEncoder.fieldArray(field, terms.stream()
                .map(LicensePolicyTerm::value)
                .map(ManifestDependencyWriter::string)
                .toList());
    }

    private static String spdxTerms(ManifestField field, List<SpdxLicenseTerm> terms) {
        return ManifestTomlValueEncoder.fieldArray(field, terms.stream()
                .map(SpdxLicenseTerm::value)
                .map(ManifestDependencyWriter::string)
                .toList());
    }

    private static String coordinateArray(List<DependencyCoordinate> coordinates) {
        return ManifestTomlValueEncoder.array(coordinates.stream()
                .sorted()
                .map(DependencyCoordinate::value)
                .map(ManifestDependencyWriter::string)
                .toList());
    }

    private static ManifestTomlValueEncoder.InlineMember member(
            String name, String value) {
        return ManifestTomlValueEncoder.member(name, string(value));
    }

    private static ManifestTomlValueEncoder.InlineMember booleanMember(String name) {
        return ManifestTomlValueEncoder.member(
                name, ManifestTomlValueEncoder.booleanValue(true));
    }

    private static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    private static Lane lane(
            ManifestPath path, ManifestField field, DependencyLane lane) {
        return new Lane(section(path), field, lane);
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }

    private record Lane(
            ManifestSection section, ManifestField field, DependencyLane lane) {
    }
}
