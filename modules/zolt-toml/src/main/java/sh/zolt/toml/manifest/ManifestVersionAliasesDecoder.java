package sh.zolt.toml.manifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSharedFields;

/** Decodes the optional authored fixed-version alias collection. */
final class ManifestVersionAliasesDecoder {
    Optional<AuthoredVersionAliases> decode(ManifestDecodeIndex index) {
        return index.section(FinalManifestPaths.VERSIONS).map(section -> {
            Map<LocalId, VersionAliasValue> entries = entries(index);
            return ManifestSemanticDiagnostics.construct(
                    section, () -> new AuthoredVersionAliases(entries));
        });
    }

    private static Map<LocalId, VersionAliasValue> entries(ManifestDecodeIndex index) {
        LinkedHashMap<LocalId, VersionAliasValue> entries = new LinkedHashMap<>();
        for (ManifestDecodeIndex.Entry entry :
                index.entries(FinalManifestSharedFields.VERSIONS_ENTRY)) {
            ValidatedManifestField field = entry.field();
            LocalId id = ManifestSemanticDiagnostics.construct(
                    field, () -> new LocalId(entry.key()));
            VersionAliasValue value = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new VersionAliasValue(ManifestTomlValues.string(field)));
            if (entries.put(id, value) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate version alias `" + id + "`.");
            }
        }
        return entries;
    }
}
