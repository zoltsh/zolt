package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.authored.AuthoredPlatforms;
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
            PlatformSelector selector = ManifestPlatformSelectorDecoder.decode(field);
            if (platforms.put(coordinate, selector) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate platform `" + coordinate + "`.");
            }
        }
        return platforms;
    }

}
