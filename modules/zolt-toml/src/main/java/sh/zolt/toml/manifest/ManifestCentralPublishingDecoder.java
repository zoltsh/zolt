package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredCentralPublishing;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestPublishingFields;

/** Decodes authored Central publishing without applying service defaults or reading secrets. */
final class ManifestCentralPublishingDecoder {
    Optional<AuthoredCentralPublishing> decode(
            ManifestDecodeIndex index,
            CentralPresenceObserver observer) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(observer, "Authored Central presence observer is required.");
        if (index.section(FinalManifestPaths.PUBLISH_CENTRAL).isEmpty()) {
            return Optional.empty();
        }

        ValidatedManifestField tokenField = ManifestSemanticDiagnostics.requiredField(
                index, FinalManifestPublishingFields.PUBLISH_CENTRAL_TOKEN_ENV);
        EnvironmentVariableName tokenEnvironment = ManifestSemanticDiagnostics.construct(
                tokenField,
                () -> new EnvironmentVariableName(ManifestTomlValues.string(tokenField)));
        ValidatedManifestField modeField = ManifestSemanticDiagnostics.requiredField(
                index, FinalManifestPublishingFields.PUBLISH_CENTRAL_MODE);
        AuthoredCentralPublishing.Mode mode = mode(modeField);
        AuthoredCentralPublishing central = ManifestSemanticDiagnostics.construct(
                modeField,
                () -> new AuthoredCentralPublishing(
                        tokenEnvironment,
                        mode,
                        Optional.empty(),
                        Optional.empty()));
        AuthoredCentralPublishing observed = central;
        central = ManifestSemanticDiagnostics.construct(tokenField, () -> {
            observer.present(observed);
            return observed;
        });

        Optional<ValidatedManifestField> nameField =
                index.field(FinalManifestPublishingFields.PUBLISH_CENTRAL_NAME);
        Optional<String> name = nameField.map(ManifestTomlValues::string);
        if (nameField.isPresent()) {
            ValidatedManifestField field = nameField.orElseThrow();
            central = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredCentralPublishing(
                            tokenEnvironment, mode, name, Optional.empty()));
        }

        Optional<ValidatedManifestField> urlField =
                index.field(FinalManifestPublishingFields.PUBLISH_CENTRAL_URL);
        if (urlField.isPresent()) {
            ValidatedManifestField field = urlField.orElseThrow();
            RepositoryUrl url = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new RepositoryUrl(ManifestTomlValues.string(field)));
            central = ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredCentralPublishing(
                            tokenEnvironment, mode, name, Optional.of(url)));
        }
        return Optional.of(central);
    }

    private static AuthoredCentralPublishing.Mode mode(ValidatedManifestField field) {
        String value = ManifestTomlValues.string(field);
        for (AuthoredCentralPublishing.Mode mode : AuthoredCentralPublishing.Mode.values()) {
            if (mode.configValue().equals(value)) {
                return mode;
            }
        }
        throw new IllegalStateException(
                "Final manifest schema accepted Central publication mode `" + value
                        + "` at `" + field.path() + "` but the model does not recognize it.");
    }

    @FunctionalInterface
    interface CentralPresenceObserver {
        void present(AuthoredCentralPublishing central);
    }
}
