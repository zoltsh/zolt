package sh.zolt.toml.manifest;

import java.util.function.Consumer;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredPackaging;

/** Cross-package test seam for the package-private final-manifest packaging decoder. */
public final class ManifestPackagingTestSupport {
    private ManifestPackagingTestSupport() {
    }

    public static AuthoredPackaging decodePackaging(String source) {
        return decodePackaging(source, ignored -> {});
    }

    public static AuthoredPackaging decodePackaging(
            String source,
            Consumer<AuthoredBom> observer) {
        ManifestBomDecoder.BomPresenceObserver adapted =
                observer == null ? null : observer::accept;
        return new ManifestPackagingDecoder().decode(
                ManifestSemanticTestSupport.index(source), adapted);
    }

    public static void decodePackagingWithNullIndex() {
        new ManifestPackagingDecoder().decode(null, ignored -> {});
    }
}
