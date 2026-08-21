package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPackagingFields;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.ManifestPath;

/** Composes the independently optional authored BOM domains. */
final class ManifestBomDecoder {
    private final ManifestBomMembersDecoder members = new ManifestBomMembersDecoder();
    private final ManifestBomVersionsDecoder versions = new ManifestBomVersionsDecoder();
    private final ManifestBomImportsDecoder imports = new ManifestBomImportsDecoder();

    Optional<AuthoredBom> decode(
            ManifestDecodeIndex index,
            BomPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored BOM presence observer is required.");
        Optional<AuthoredBom.Members> decodedMembers = members.decode(
                index,
                decoded -> observer.present(new AuthoredBom(
                        Optional.of(decoded), Optional.empty(), Optional.empty())));
        observeCollectionPresence(
                index,
                FinalManifestPaths.BOM_VERSIONS,
                new AuthoredBom(
                        decodedMembers, Optional.of(Map.of()), Optional.empty()),
                observer);
        Optional<Map<DependencyCoordinate, AuthoredBom.Version>> decodedVersions =
                versions.decode(index);
        observeCollectionPresence(
                index,
                FinalManifestPaths.BOM_IMPORTS,
                new AuthoredBom(
                        decodedMembers, decodedVersions, Optional.of(Map.of())),
                observer);
        Optional<Map<DependencyCoordinate, PlatformSelector>> decodedImports =
                imports.decode(index);
        if (decodedMembers.isEmpty()
                && decodedVersions.isEmpty()
                && decodedImports.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthoredBom(
                decodedMembers, decodedVersions, decodedImports));
    }

    private static void observeCollectionPresence(
            ManifestDecodeIndex index,
            ManifestPath path,
            AuthoredBom partial,
            BomPresenceObserver observer) {
        index.section(path)
                .filter(section -> section.source().authoredTable())
                .ifPresent(section -> ManifestSemanticDiagnostics.construct(section, () -> {
                    observer.present(partial);
                    return partial;
                }));
    }

    @FunctionalInterface
    interface BomPresenceObserver {
        void present(AuthoredBom bom);
    }
}

/** Decodes an explicitly authored BOM member selection and exact exclusions. */
final class ManifestBomMembersDecoder {
    Optional<AuthoredBom.Members> decode(
            ManifestDecodeIndex index,
            DecodedMembersObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Decoded BOM members observer is required.");
        Optional<ValidatedManifestField> membersField =
                index.field(FinalManifestPackagingFields.BOM_MEMBERS);
        Optional<ValidatedManifestField> excludeField =
                index.field(FinalManifestPackagingFields.BOM_EXCLUDE);
        if (membersField.isEmpty() && excludeField.isEmpty()) {
            return Optional.empty();
        }

        ValidatedManifestField requiredMembers = ManifestSemanticDiagnostics.requiredField(
                index, FinalManifestPackagingFields.BOM_MEMBERS);
        AuthoredBom.MemberSelection selection = selection(requiredMembers);
        AuthoredBom.Members selected = ManifestSemanticDiagnostics.construct(
                requiredMembers,
                () -> {
                    AuthoredBom.Members value =
                            new AuthoredBom.Members(selection, List.of());
                    observer.decoded(value);
                    return value;
                });
        if (excludeField.isEmpty()) {
            return Optional.of(selected);
        }

        ValidatedManifestField exclusions = excludeField.orElseThrow();
        if (!(selection instanceof AuthoredBom.AllMembers)) {
            return Optional.of(ManifestSemanticDiagnostics.construct(exclusions, () -> {
                throw new IllegalArgumentException(
                        "BOM member exclusions are valid only with `members = true`.");
            }));
        }
        return Optional.of(exclusions(selection, exclusions));
    }

    private static AuthoredBom.MemberSelection selection(ValidatedManifestField field) {
        if (ManifestTomlValues.isBoolean(field)) {
            return ManifestSemanticDiagnostics.construct(field, () -> {
                if (!ManifestTomlValues.booleanValue(field)) {
                    throw new IllegalArgumentException(
                            "BOM members must be `true` or a nonempty array of exact workspace member paths.");
                }
                return new AuthoredBom.AllMembers();
            });
        }

        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<WorkspaceMemberPath> paths = new ArrayList<>(authored.size());
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            paths.add(ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new WorkspaceMemberPath(authored.get(index))));
            ManifestSemanticDiagnostics.construct(
                    field, index, () -> new AuthoredBom.ExplicitMembers(paths));
        }
        return ManifestSemanticDiagnostics.construct(
                field, () -> new AuthoredBom.ExplicitMembers(paths));
    }

    private static AuthoredBom.Members exclusions(
            AuthoredBom.MemberSelection selection,
            ValidatedManifestField field) {
        List<String> authored = ManifestTomlValues.strings(field);
        ArrayList<WorkspaceMemberPath> paths = new ArrayList<>(authored.size());
        for (int item = 0; item < authored.size(); item++) {
            int index = item;
            paths.add(ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> new WorkspaceMemberPath(authored.get(index))));
            ManifestSemanticDiagnostics.construct(
                    field, index, () -> new AuthoredBom.Members(selection, paths));
        }
        return ManifestSemanticDiagnostics.construct(
                field, () -> new AuthoredBom.Members(selection, paths));
    }

    @FunctionalInterface
    interface DecodedMembersObserver {
        void decoded(AuthoredBom.Members members);
    }
}

/** Decodes authored BOM version constraints without resolving version aliases. */
final class ManifestBomVersionsDecoder {
    Optional<Map<DependencyCoordinate, AuthoredBom.Version>> decode(
            ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        List<ManifestDecodeIndex.Entry> entries =
                index.entries(FinalManifestPackagingFields.BOM_VERSIONS_ENTRY);
        Optional<ValidatedManifestSection> section = index
                .section(FinalManifestPaths.BOM_VERSIONS)
                .filter(candidate -> candidate.source().authoredTable());
        if (section.isEmpty()
                && entries.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashMap<DependencyCoordinate, AuthoredBom.Version> versions =
                new LinkedHashMap<>();
        for (ManifestDecodeIndex.Entry entry : entries) {
            ValidatedManifestField field = entry.field();
            DependencyCoordinate coordinate = ManifestSemanticDiagnostics.construct(
                    field, () -> new DependencyCoordinate(entry.key()));
            AuthoredBom.Version version = decodeVersion(field);
            if (versions.put(coordinate, version) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate BOM version `"
                                + coordinate + "`.");
            }
        }
        return Optional.of(ManifestModelValues.immutableSortedMap(
                versions,
                DependencyCoordinate::compareTo,
                "BOM version coordinate",
                "Authored BOM version"));
    }

    private static AuthoredBom.Version decodeVersion(ValidatedManifestField field) {
        PlatformSelector selector = ManifestPlatformSelectorDecoder.decode(field);
        if (!ManifestTomlValues.isInlineObject(field)) {
            return ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredBom.Version(
                            selector, Optional.empty(), Optional.empty()));
        }

        ManifestInlineTable table = ManifestTomlValues.inlineObject(field);
        AuthoredBom.Version version = new AuthoredBom.Version(
                selector, Optional.empty(), Optional.empty());
        Optional<String> classifier = table.optionalString(
                FinalManifestObjectShapes.BOM_VERSION_CLASSIFIER);
        if (classifier.isPresent()) {
            version = ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.BOM_VERSION_CLASSIFIER,
                    () -> new AuthoredBom.Version(
                            selector, classifier, Optional.empty()));
        }
        Optional<String> type = table.optionalString(
                FinalManifestObjectShapes.BOM_VERSION_TYPE);
        if (type.isPresent()) {
            AuthoredBom.Version prior = version;
            version = ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.BOM_VERSION_TYPE,
                    () -> new AuthoredBom.Version(
                            selector, prior.classifier(), type));
        }
        return version;
    }
}

/** Decodes authored BOM imports without resolving version aliases. */
final class ManifestBomImportsDecoder {
    Optional<Map<DependencyCoordinate, PlatformSelector>> decode(
            ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        List<ManifestDecodeIndex.Entry> entries =
                index.entries(FinalManifestPackagingFields.BOM_IMPORTS_ENTRY);
        Optional<ValidatedManifestSection> section = index
                .section(FinalManifestPaths.BOM_IMPORTS)
                .filter(candidate -> candidate.source().authoredTable());
        if (section.isEmpty()
                && entries.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashMap<DependencyCoordinate, PlatformSelector> imports =
                new LinkedHashMap<>();
        for (ManifestDecodeIndex.Entry entry : entries) {
            ValidatedManifestField field = entry.field();
            DependencyCoordinate coordinate = ManifestSemanticDiagnostics.construct(
                    field, () -> new DependencyCoordinate(entry.key()));
            PlatformSelector selector = ManifestPlatformSelectorDecoder.decode(field);
            if (imports.put(coordinate, selector) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate BOM import `"
                                + coordinate + "`.");
            }
        }
        return Optional.of(ManifestModelValues.immutableSortedMap(
                imports,
                DependencyCoordinate::compareTo,
                "BOM import coordinate",
                "Authored BOM import"));
    }
}
