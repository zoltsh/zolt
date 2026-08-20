package sh.zolt.toml.manifest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredNativeImage;
import sh.zolt.toml.schema.FinalManifestPackagingFields;

/** Decodes authored native-image settings without applying project or output defaults. */
final class ManifestNativeImageDecoder {
    Optional<AuthoredNativeImage> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> nameField =
                index.field(FinalManifestPackagingFields.NATIVE_NAME);
        Optional<ValidatedManifestField> outputField =
                index.field(FinalManifestPackagingFields.NATIVE_OUTPUT);
        Optional<ValidatedManifestField> argsField =
                index.field(FinalManifestPackagingFields.NATIVE_ARGS);
        if (nameField.isEmpty()
                && outputField.isEmpty()
                && argsField.isEmpty()) {
            return Optional.empty();
        }

        Optional<String> name = nameField.map(ManifestNativeImageDecoder::name);
        Optional<ManifestRelativePath> output = outputField.map(field ->
                ManifestSemanticDiagnostics.construct(
                        field,
                        () -> new ManifestRelativePath(ManifestTomlValues.string(field))));
        Optional<List<String>> args = argsField.map(ManifestTomlValues::strings);
        ValidatedManifestField anchor = nameField
                .or(() -> outputField)
                .or(() -> argsField)
                .orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor, () -> new AuthoredNativeImage(name, output, args)));
    }

    private static String name(ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        return ManifestSemanticDiagnostics.construct(field, () -> {
            ManifestModelValues.requireNonBlank(value, "Native image name");
            return value;
        });
    }
}
