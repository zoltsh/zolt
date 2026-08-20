package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.toml.schema.FinalManifestObjectShapes;

/** Decodes the shared fixed-version or version-alias selector union. */
final class ManifestPlatformSelectorDecoder {
    private ManifestPlatformSelectorDecoder() {
    }

    static PlatformSelector decode(ValidatedManifestField field) {
        Objects.requireNonNull(field, "Validated platform-selector field is required.");
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
