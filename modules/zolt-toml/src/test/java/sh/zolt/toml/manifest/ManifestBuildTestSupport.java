package sh.zolt.toml.manifest;

import sh.zolt.manifest.authored.AuthoredBuildConfiguration;

/** Cross-package test seam for package-private final-manifest build decoders. */
public final class ManifestBuildTestSupport {
    private ManifestBuildTestSupport() {
    }

    public static AuthoredBuildConfiguration decodeBuildConfiguration(String source) {
        return new ManifestBuildConfigurationDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }

    public static void decodeBuildConfigurationWithNullIndex() {
        new ManifestBuildConfigurationDecoder().decode(null);
    }
}
