package sh.zolt.toml.manifest;

import java.util.Optional;
import java.util.function.Consumer;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCompiler;
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

    public static Optional<AuthoredBuild> decodeBuild(
            String source, Consumer<AuthoredBuild> observer) {
        ManifestBuildDecoder.BuildPresenceObserver adapted =
                observer == null ? null : observer::accept;
        return new ManifestBuildDecoder().decode(
                ManifestSemanticTestSupport.index(source), adapted);
    }

    public static void decodeBuildWithNullIndex() {
        new ManifestBuildDecoder().decode(null, ignored -> {});
    }

    public static void decodeBuildWithNullObserver() {
        new ManifestBuildDecoder().decode(ManifestSemanticTestSupport.index(""), null);
    }

    public static Optional<AuthoredCompiler> decodeCompiler(
            String source, Consumer<AuthoredCompiler> observer) {
        ManifestCompilerDecoder.CompilerPresenceObserver adapted =
                observer == null ? null : observer::accept;
        return new ManifestCompilerDecoder().decode(
                ManifestSemanticTestSupport.index(source), adapted);
    }

    public static void decodeCompilerWithNullIndex() {
        new ManifestCompilerDecoder().decode(null, ignored -> {});
    }

    public static void decodeCompilerWithNullObserver() {
        new ManifestCompilerDecoder().decode(ManifestSemanticTestSupport.index(""), null);
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
