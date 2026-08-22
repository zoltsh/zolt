package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.ManifestObjectMember;

/** Decodes one authored dependency entry without resolving its selector. */
final class ManifestDependencyEntryDecoder {
    AuthoredDependency decode(
            DependencyLane lane,
            ManifestDecodeIndex.Entry entry) {
        Objects.requireNonNull(lane, "Dependency lane is required.");
        Objects.requireNonNull(entry, "Manifest dependency entry is required.");
        ValidatedManifestField field = entry.field();
        DependencyCoordinate coordinate = ManifestSemanticDiagnostics.construct(
                field, () -> new DependencyCoordinate(entry.key()));
        if (ManifestTomlValues.isString(field)) {
            DependencySelector selector = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new DependencySelector.FixedVersion(
                            ManifestTomlValues.string(field)));
            return dependency(field, lane, coordinate, selector);
        }

        ManifestInlineTable table = ManifestTomlValues.inlineObject(field);
        DependencySelector selector = selector(table);
        AuthoredDependency dependency = dependency(field, lane, coordinate, selector);
        dependency = optional(dependency, table);
        dependency = publishOnly(dependency, table);
        dependency = classifier(dependency, table);
        dependency = type(dependency, table);
        return exclusions(dependency, table);
    }

    private static DependencySelector selector(ManifestInlineTable table) {
        Optional<String> version = table.optionalString(
                FinalManifestObjectShapes.DEPENDENCY_VERSION);
        if (version.isPresent()) {
            return ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.DEPENDENCY_VERSION,
                    () -> new DependencySelector.FixedVersion(version.orElseThrow()));
        }
        Optional<String> versionRef = table.optionalString(
                FinalManifestObjectShapes.DEPENDENCY_VERSION_REF);
        if (versionRef.isPresent()) {
            return ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.DEPENDENCY_VERSION_REF,
                    () -> new DependencySelector.VersionReference(
                            new LocalId(versionRef.orElseThrow())));
        }
        Optional<Boolean> managed = table.optionalBoolean(
                FinalManifestObjectShapes.DEPENDENCY_MANAGED);
        if (managed.isPresent()) {
            return trueSelector(
                    table,
                    FinalManifestObjectShapes.DEPENDENCY_MANAGED,
                    managed.orElseThrow(),
                    new DependencySelector.Managed());
        }
        Optional<Boolean> workspace = table.optionalBoolean(
                FinalManifestObjectShapes.DEPENDENCY_WORKSPACE);
        if (workspace.isPresent()) {
            return trueSelector(
                    table,
                    FinalManifestObjectShapes.DEPENDENCY_WORKSPACE,
                    workspace.orElseThrow(),
                    new DependencySelector.Workspace());
        }
        throw new IllegalStateException("Validated dependency entry has no selector.");
    }

    private static DependencySelector trueSelector(
            ManifestInlineTable table,
            ManifestObjectMember member,
            boolean value,
            DependencySelector selector) {
        return ManifestSemanticDiagnostics.construct(table, member, () -> {
            if (!value) {
                throw new IllegalArgumentException(
                        "Dependency selector `" + member.name() + "` must be true.");
            }
            return selector;
        });
    }

    private static AuthoredDependency optional(
            AuthoredDependency dependency,
            ManifestInlineTable table) {
        return table.optionalBoolean(FinalManifestObjectShapes.DEPENDENCY_OPTIONAL)
                .map(value -> withMetadata(
                        dependency,
                        table,
                        FinalManifestObjectShapes.DEPENDENCY_OPTIONAL,
                        metadata -> new AuthoredDependencyMetadata(
                                value,
                                metadata.publishOnly(),
                                metadata.classifier(),
                                metadata.type(),
                                metadata.exclusions())))
                .orElse(dependency);
    }

    private static AuthoredDependency publishOnly(
            AuthoredDependency dependency,
            ManifestInlineTable table) {
        return table.optionalBoolean(FinalManifestObjectShapes.DEPENDENCY_PUBLISH_ONLY)
                .map(value -> withMetadata(
                        dependency,
                        table,
                        FinalManifestObjectShapes.DEPENDENCY_PUBLISH_ONLY,
                        metadata -> new AuthoredDependencyMetadata(
                                metadata.optional(),
                                value,
                                metadata.classifier(),
                                metadata.type(),
                                metadata.exclusions())))
                .orElse(dependency);
    }

    private static AuthoredDependency classifier(
            AuthoredDependency dependency,
            ManifestInlineTable table) {
        return table.optionalString(FinalManifestObjectShapes.DEPENDENCY_CLASSIFIER)
                .map(value -> withMetadata(
                        dependency,
                        table,
                        FinalManifestObjectShapes.DEPENDENCY_CLASSIFIER,
                        metadata -> new AuthoredDependencyMetadata(
                                metadata.optional(),
                                metadata.publishOnly(),
                                Optional.of(value),
                                metadata.type(),
                                metadata.exclusions())))
                .orElse(dependency);
    }

    private static AuthoredDependency type(
            AuthoredDependency dependency,
            ManifestInlineTable table) {
        return table.optionalString(FinalManifestObjectShapes.DEPENDENCY_TYPE)
                .map(value -> withMetadata(
                        dependency,
                        table,
                        FinalManifestObjectShapes.DEPENDENCY_TYPE,
                        metadata -> {
                            requireExternalArtifactSelector(dependency);
                            return new AuthoredDependencyMetadata(
                                    metadata.optional(),
                                    metadata.publishOnly(),
                                    metadata.classifier(),
                                    Optional.of(value),
                                    metadata.exclusions());
                        }))
                .orElse(dependency);
    }

    /**
     * Design §9.5 rejects {@code type} on a {@code workspace = true} declaration outright, whatever
     * value it names. The authored model normalizes an explicit {@code jar} away as the default
     * variant, so that one spelling would otherwise arrive metadata-free and slip past the check every
     * other artifact field still trips inside {@link AuthoredDependency}.
     */
    private static void requireExternalArtifactSelector(AuthoredDependency dependency) {
        if (dependency.selector() instanceof DependencySelector.Workspace) {
            throw new IllegalArgumentException(
                    "Workspace dependencies cannot declare classifier, type, or exclusion metadata.");
        }
    }

    private static AuthoredDependency exclusions(
            AuthoredDependency dependency,
            ManifestInlineTable table) {
        Optional<List<String>> authored = table.optionalStrings(
                FinalManifestObjectShapes.DEPENDENCY_EXCLUDE);
        if (authored.isEmpty()) {
            return dependency;
        }
        ArrayList<DependencyCoordinate> exclusions = new ArrayList<>();
        Set<DependencyCoordinate> seen = new LinkedHashSet<>();
        AuthoredDependency current = dependency;
        for (int index = 0; index < authored.orElseThrow().size(); index++) {
            String value = authored.orElseThrow().get(index);
            DependencyCoordinate exclusion = ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.DEPENDENCY_EXCLUDE,
                    index,
                    () -> new DependencyCoordinate(value));
            ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.DEPENDENCY_EXCLUDE,
                    index,
                    () -> requireUniqueExclusion(seen, exclusion));
            exclusions.add(exclusion);
            current = withMetadata(
                    current,
                    table,
                    FinalManifestObjectShapes.DEPENDENCY_EXCLUDE,
                    index,
                    metadata -> new AuthoredDependencyMetadata(
                            metadata.optional(),
                            metadata.publishOnly(),
                            metadata.classifier(),
                            metadata.type(),
                            exclusions));
        }
        return current;
    }

    private static DependencyCoordinate requireUniqueExclusion(
            Set<DependencyCoordinate> seen,
            DependencyCoordinate exclusion) {
        if (!seen.add(exclusion)) {
            throw new IllegalArgumentException(
                    "Dependency exclusion `" + exclusion + "` is declared more than once.");
        }
        return exclusion;
    }

    private static AuthoredDependency dependency(
            ValidatedManifestField field,
            DependencyLane lane,
            DependencyCoordinate coordinate,
            DependencySelector selector) {
        return ManifestSemanticDiagnostics.construct(
                field,
                () -> new AuthoredDependency(
                        lane,
                        coordinate,
                        selector,
                        AuthoredDependencyMetadata.none()));
    }

    private static AuthoredDependency withMetadata(
            AuthoredDependency dependency,
            ManifestInlineTable table,
            ManifestObjectMember member,
            Function<AuthoredDependencyMetadata, AuthoredDependencyMetadata> update) {
        return ManifestSemanticDiagnostics.construct(
                table,
                member,
                () -> copyWithMetadata(dependency, update.apply(dependency.metadata())));
    }

    private static AuthoredDependency withMetadata(
            AuthoredDependency dependency,
            ManifestInlineTable table,
            ManifestObjectMember member,
            int index,
            Function<AuthoredDependencyMetadata, AuthoredDependencyMetadata> update) {
        return ManifestSemanticDiagnostics.construct(
                table,
                member,
                index,
                () -> copyWithMetadata(dependency, update.apply(dependency.metadata())));
    }

    private static AuthoredDependency copyWithMetadata(
            AuthoredDependency dependency,
            AuthoredDependencyMetadata metadata) {
        return new AuthoredDependency(
                dependency.lane(),
                dependency.coordinate(),
                dependency.selector(),
                metadata);
    }
}
