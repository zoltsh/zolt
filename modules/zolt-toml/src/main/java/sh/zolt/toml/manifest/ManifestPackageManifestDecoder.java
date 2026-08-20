package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredPackageManifest;
import sh.zolt.toml.schema.FinalManifestPackagingFields;
import sh.zolt.toml.schema.FinalManifestPaths;

/** Decodes the optional authored JAR manifest attribute collection. */
final class ManifestPackageManifestDecoder {
    Optional<AuthoredPackageManifest> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        List<ManifestDecodeIndex.Entry> entries =
                index.entries(FinalManifestPackagingFields.PACKAGE_MANIFEST_ENTRY);
        if (index.section(FinalManifestPaths.PACKAGE_MANIFEST).isEmpty()
                && entries.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        AuthoredPackageManifest manifest = new AuthoredPackageManifest(Map.of());
        for (ManifestDecodeIndex.Entry entry : entries) {
            ValidatedManifestField field = entry.field();
            String prior = attributes.put(
                    entry.key(), ManifestTomlValues.string(field));
            if (prior != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate package manifest attribute `"
                                + entry.key() + "`.");
            }
            manifest = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredPackageManifest(attributes));
        }
        return Optional.of(manifest);
    }
}
