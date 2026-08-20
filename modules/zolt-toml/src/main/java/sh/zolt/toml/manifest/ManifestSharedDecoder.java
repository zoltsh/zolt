package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredVersionAliases;

/** Coordinates shared authored domains without applying workspace composition. */
final class ManifestSharedDecoder {
    private final ManifestVersionAliasesDecoder versions = new ManifestVersionAliasesDecoder();
    private final ManifestDependencyRepositoriesDecoder repositories =
            new ManifestDependencyRepositoriesDecoder();
    private final ManifestCredentialsDecoder credentials = new ManifestCredentialsDecoder();
    private final ManifestPlatformsDecoder platforms = new ManifestPlatformsDecoder();

    Decoded decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        return new Decoded(
                versions.decode(index),
                repositories.decode(index),
                credentials.decode(index),
                platforms.decode(index));
    }

    record Decoded(
            Optional<AuthoredVersionAliases> versions,
            Optional<AuthoredDependencyRepositories> repositories,
            Optional<AuthoredCredentials> credentials,
            Optional<AuthoredPlatforms> platforms) {
        Decoded {
            versions = Objects.requireNonNull(versions, "Decoded versions must not be null.");
            repositories = Objects.requireNonNull(
                    repositories, "Decoded repositories must not be null.");
            credentials = Objects.requireNonNull(
                    credentials, "Decoded credentials must not be null.");
            platforms = Objects.requireNonNull(platforms, "Decoded platforms must not be null.");
        }
    }
}
