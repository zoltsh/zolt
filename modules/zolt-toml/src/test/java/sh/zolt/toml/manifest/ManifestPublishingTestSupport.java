package sh.zolt.toml.manifest;

import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;

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
}
