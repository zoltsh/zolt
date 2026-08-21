package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestPublishingFields;

/** Decodes authored publication signing without inspecting the execution environment. */
final class ManifestPublicationSigningDecoder {
    Optional<AuthoredPublicationSigning> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        if (index.section(FinalManifestPaths.PUBLISH_SIGNING).isEmpty()) {
            return Optional.empty();
        }

        ValidatedManifestField methodField = ManifestSemanticDiagnostics.requiredField(
                index, FinalManifestPublishingFields.PUBLISH_SIGNING_METHOD);
        AuthoredPublicationSigning.Method method = method(methodField);
        AuthoredPublicationSigning signing = ManifestSemanticDiagnostics.construct(
                methodField,
                () -> new AuthoredPublicationSigning(
                        method, Optional.empty(), Optional.empty()));

        Optional<ValidatedManifestField> keyField =
                index.field(FinalManifestPublishingFields.PUBLISH_SIGNING_KEY_ID);
        Optional<String> keyId = keyField.map(ManifestTomlValues::string);
        if (keyField.isPresent()) {
            ValidatedManifestField field = keyField.orElseThrow();
            signing = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredPublicationSigning(
                            method, keyId, Optional.empty()));
        }

        Optional<ValidatedManifestField> passphraseField = index.field(
                FinalManifestPublishingFields.PUBLISH_SIGNING_PASSPHRASE_ENV);
        if (passphraseField.isPresent()) {
            ValidatedManifestField field = passphraseField.orElseThrow();
            EnvironmentVariableName environment = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new EnvironmentVariableName(ManifestTomlValues.string(field)));
            signing = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredPublicationSigning(
                            method, keyId, Optional.of(environment)));
        }
        return Optional.of(signing);
    }

    private static AuthoredPublicationSigning.Method method(
            ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        for (AuthoredPublicationSigning.Method method
                : AuthoredPublicationSigning.Method.values()) {
            if (method.configValue().equals(value)) {
                return method;
            }
        }
        throw new IllegalStateException(
                "Final manifest schema accepted publication signing method `" + value
                        + "` at `" + field.path() + "` but the model does not recognize it.");
    }
}
