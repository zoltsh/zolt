package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredCentralPublishing;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;
import sh.zolt.manifest.authored.AuthoredPublishing;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestPublishingFields;

/** Composes the complete authored publishing domain in canonical schema order. */
final class ManifestPublishingDecoder {
    private final ManifestPublicationRoutesDecoder routesDecoder =
            new ManifestPublicationRoutesDecoder();
    private final ManifestPublicationRepositoriesDecoder repositoriesDecoder =
            new ManifestPublicationRepositoriesDecoder();
    private final ManifestPublicationSigningDecoder signingDecoder =
            new ManifestPublicationSigningDecoder();
    private final ManifestCentralPublishingDecoder centralDecoder =
            new ManifestCentralPublishingDecoder();

    Optional<AuthoredPublishing> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<AuthoredPublicationRoutes> routes = routesDecoder.decode(index);
        Optional<Map<LocalId, AuthoredPublicationRepository>> decodedRepositories =
                repositoriesDecoder.decode(index);
        Map<LocalId, AuthoredPublicationRepository> repositories =
                decodedRepositories.orElseGet(Map::of);
        validateRoutes(index, routes, repositories);

        Optional<AuthoredPublicationSigning> signing = signingDecoder.decode(index);
        Optional<AuthoredCentralPublishing> central = centralDecoder.decode(
                index,
                partial -> new AuthoredPublishing(
                        routes, repositories, signing, Optional.of(partial)));
        if (routes.isEmpty()
                && decodedRepositories.isEmpty()
                && signing.isEmpty()
                && central.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AuthoredPublishing(
                routes, repositories, signing, central));
    }

    private static void validateRoutes(
            ManifestDecodeIndex index,
            Optional<AuthoredPublicationRoutes> routes,
            Map<LocalId, AuthoredPublicationRepository> repositories) {
        if (routes.isEmpty()) {
            return;
        }
        AuthoredPublicationRoutes decoded = routes.orElseThrow();
        if (decoded.release().isPresent()) {
            ValidatedManifestField field = index
                    .field(FinalManifestPublishingFields.PUBLISH_RELEASE)
                    .orElseThrow(() -> new IllegalStateException(
                            "Decoded release route is missing its retained field."));
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredPublishing(
                            Optional.of(new AuthoredPublicationRoutes(
                                    decoded.release(), Optional.empty())),
                            repositories,
                            Optional.empty(),
                            Optional.empty()));
        }
        if (decoded.snapshot().isPresent()) {
            ValidatedManifestField field = index
                    .field(FinalManifestPublishingFields.PUBLISH_SNAPSHOT)
                    .orElseThrow(() -> new IllegalStateException(
                            "Decoded snapshot route is missing its retained field."));
            ManifestSemanticDiagnostics.construct(
                    field,
                    () -> new AuthoredPublishing(
                            routes,
                            repositories,
                            Optional.empty(),
                            Optional.empty()));
        }
    }
}

/** Decodes authored release and snapshot publication repository selections. */
final class ManifestPublicationRoutesDecoder {
    Optional<AuthoredPublicationRoutes> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<ValidatedManifestField> releaseField =
                index.field(FinalManifestPublishingFields.PUBLISH_RELEASE);
        Optional<ValidatedManifestField> snapshotField =
                index.field(FinalManifestPublishingFields.PUBLISH_SNAPSHOT);
        if (releaseField.isEmpty() && snapshotField.isEmpty()) {
            return Optional.empty();
        }

        Optional<LocalId> release = route(releaseField);
        Optional<LocalId> snapshot = route(snapshotField);
        ValidatedManifestField anchor = releaseField
                .or(() -> snapshotField)
                .orElseThrow();
        return Optional.of(ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredPublicationRoutes(release, snapshot)));
    }

    private static Optional<LocalId> route(
            Optional<ValidatedManifestField> field) {
        return field.map(value -> ManifestSemanticDiagnostics.construct(
                value,
                () -> new LocalId(ManifestTomlValues.string(value))));
    }
}

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
        return ManifestAuthoredSymbols.model(
                field,
                ManifestTomlValues.string(field),
                AuthoredPublicationSigning.Method.values(),
                AuthoredPublicationSigning.Method::configValue,
                "publication signing method");
    }
}

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
        return ManifestAuthoredSymbols.model(
                field,
                ManifestTomlValues.string(field),
                AuthoredCentralPublishing.Mode.values(),
                AuthoredCentralPublishing.Mode::configValue,
                "Central publication mode");
    }

    @FunctionalInterface
    interface CentralPresenceObserver {
        void present(AuthoredCentralPublishing central);
    }
}
