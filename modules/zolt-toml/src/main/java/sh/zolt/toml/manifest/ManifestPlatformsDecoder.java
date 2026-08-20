package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSharedFields;

/** Decodes fixed and alias-referenced imported platform selectors. */
final class ManifestPlatformsDecoder {
    Optional<AuthoredPlatforms> decode(ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.PLATFORMS).map(section -> {
            Map<DependencyCoordinate, PlatformSelector> entries = decodeEntries(index);
            return ManifestSemanticDiagnostics.construct(
                    section, () -> new AuthoredPlatforms(entries));
        });
    }

    private static Map<DependencyCoordinate, PlatformSelector> decodeEntries(
            ManifestDecodeIndex index) {
        LinkedHashMap<DependencyCoordinate, PlatformSelector> platforms = new LinkedHashMap<>();
        for (ManifestDecodeIndex.Entry entry :
                index.entries(FinalManifestSharedFields.PLATFORMS_ENTRY)) {
            ValidatedManifestField field = entry.field();
            DependencyCoordinate coordinate = ManifestSemanticDiagnostics.construct(
                    field, () -> new DependencyCoordinate(entry.key()));
            PlatformSelector selector = decodeSelector(field);
            if (platforms.put(coordinate, selector) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate platform `" + coordinate + "`.");
            }
        }
        return platforms;
    }

    private static PlatformSelector decodeSelector(ValidatedManifestField field) {
        if (ManifestTomlValues.isString(field)) {
            return ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new PlatformSelector.FixedVersion(
                            ManifestTomlValues.string(field)));
        }
        ManifestInlineTable table = ManifestTomlValues.inlineObject(field);
        Optional<String> version = table.optionalString(
                FinalManifestObjectShapes.PLATFORM_VERSION);
        if (version.isPresent()) {
            return ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.PLATFORM_VERSION,
                    () -> new PlatformSelector.FixedVersion(version.orElseThrow()));
        }
        String versionRef = table.requiredString(
                FinalManifestObjectShapes.PLATFORM_VERSION_REF);
        return ManifestSemanticDiagnostics.construct(
                table,
                FinalManifestObjectShapes.PLATFORM_VERSION_REF,
                () -> new PlatformSelector.VersionReference(new LocalId(versionRef)));
    }
}
