package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPackagingFields;
import sh.zolt.toml.schema.FinalManifestPaths;

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
