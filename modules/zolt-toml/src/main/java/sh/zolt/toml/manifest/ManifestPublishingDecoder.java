package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredCentralPublishing;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;
import sh.zolt.manifest.authored.AuthoredPublishing;
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
