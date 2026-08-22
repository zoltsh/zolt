package sh.zolt.toml.manifest;

import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredVersionAliases;

/** Cross-package test seam for the package-private final-manifest shared decoder. */
public final class ManifestSharedTestSupport {
    private ManifestSharedTestSupport() {
    }

    public static Decoded decodeShared(String source) {
        ManifestSharedDecoder.Decoded decoded = new ManifestSharedDecoder().decode(
                ManifestSemanticTestSupport.index(source));
        return new Decoded(
                decoded.versions(),
                decoded.repositories(),
                decoded.credentials(),
                decoded.platforms());
    }

    public record Decoded(
            Optional<AuthoredVersionAliases> versions,
            Optional<AuthoredDependencyRepositories> repositories,
            Optional<AuthoredCredentials> credentials,
            Optional<AuthoredPlatforms> platforms) {}
}
