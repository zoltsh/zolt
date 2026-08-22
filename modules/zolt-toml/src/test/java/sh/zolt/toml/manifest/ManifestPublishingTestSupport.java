package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredCentralPublishing;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;
import sh.zolt.manifest.authored.AuthoredPublishing;

/** Cross-package test seam for package-private final-manifest publishing decoders. */
public final class ManifestPublishingTestSupport {
    private ManifestPublishingTestSupport() {
    }

    public static Optional<AuthoredPublicationRoutes> decodeRoutes(String source) {
        return new ManifestPublicationRoutesDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    public static void decodeRoutesWithNullIndex() {
        new ManifestPublicationRoutesDecoder().decode(null);
    }

    public static Optional<Map<LocalId, AuthoredPublicationRepository>>
            decodeRepositories(String source) {
        return new ManifestPublicationRepositoriesDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    public static void decodeRepositoriesWithNullIndex() {
        new ManifestPublicationRepositoriesDecoder().decode(null);
    }

    public static Optional<AuthoredPublicationSigning> decodeSigning(String source) {
        return new ManifestPublicationSigningDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    public static void decodeSigningWithNullIndex() {
        new ManifestPublicationSigningDecoder().decode(null);
    }

    public static Optional<AuthoredCentralPublishing> decodeCentral(String source) {
        return decodeCentral(source, ignored -> {});
    }

    public static Optional<AuthoredCentralPublishing> decodeCentral(
            String source,
            Consumer<AuthoredCentralPublishing> observer) {
        ManifestCentralPublishingDecoder.CentralPresenceObserver adapted =
                observer == null ? null : observer::accept;
        return new ManifestCentralPublishingDecoder().decode(
                ManifestSemanticTestSupport.index(source), adapted);
    }

    public static void decodeCentralWithNullIndex() {
        new ManifestCentralPublishingDecoder().decode(null, ignored -> {});
    }

    public static void decodeCentralWithNullObserver(String source) {
        new ManifestCentralPublishingDecoder().decode(
                ManifestSemanticTestSupport.index(source), null);
    }

    public static Optional<AuthoredPublishing> decodePublishing(String source) {
        return decodePublishing(source, ignored -> {});
    }

    public static Optional<AuthoredPublishing> decodePublishing(
            String source,
            Consumer<AuthoredPublishing> observer) {
        ManifestPublishingDecoder.PublishingPresenceObserver adapted =
                observer == null ? null : observer::accept;
        return new ManifestPublishingDecoder().decode(
                ManifestSemanticTestSupport.index(source), adapted);
    }

    public static void decodePublishingWithNullIndex() {
        new ManifestPublishingDecoder().decode(null, ignored -> {});
    }
}
