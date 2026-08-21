package sh.zolt.toml.manifest;

import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;

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
}
