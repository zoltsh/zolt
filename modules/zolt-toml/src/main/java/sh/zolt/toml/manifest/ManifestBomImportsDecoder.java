package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.toml.schema.FinalManifestPackagingFields;
import sh.zolt.toml.schema.FinalManifestPaths;

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
