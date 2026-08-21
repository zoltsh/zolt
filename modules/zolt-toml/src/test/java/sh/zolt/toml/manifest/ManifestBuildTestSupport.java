package sh.zolt.toml.manifest;

import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;

/** Cross-package test seam for package-private final-manifest build decoders. */
public final class ManifestBuildTestSupport {
    private ManifestBuildTestSupport() {
    }

    public static AuthoredBuildConfiguration decodeBuildConfiguration(String source) {
        return decode(source).build();
    }

    public static Optional<AuthoredGeneratedSources> decodeGeneratedSources(String source) {
        return decode(source).generated();
    }

    public static void decodeBuildConfigurationWithNullIndex() {
        new ManifestBuildConfigurationDecoder().decode(null);
    }

    public static void constructBuildDomainsWithNullConfiguration() {
        new ManifestBuildConfigurationDecoder.Decoded(null, Optional.empty());
    }

    public static void constructBuildDomainsWithNullGeneratedSources() {
        new ManifestBuildConfigurationDecoder.Decoded(AuthoredBuildConfiguration.empty(), null);
    }

    private static ManifestBuildConfigurationDecoder.Decoded decode(String source) {
        return new ManifestBuildConfigurationDecoder().decode(
                ManifestSemanticTestSupport.index(source));
    }
}
