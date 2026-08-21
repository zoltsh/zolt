package sh.zolt.toml.manifest;

import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;

/** Cross-package test seam for package-private final-manifest dependency decoders. */
public final class ManifestDependencyTestSupport {
    private ManifestDependencyTestSupport() {
    }

    public static Decoded decodeDependencies(String source) {
        ManifestDependencyDecoder.Decoded decoded = new ManifestDependencyDecoder().decode(
                ManifestSemanticTestSupport.index(source));
        return new Decoded(
                decoded.dependencies(), decoded.constraints(), decoded.policy());
    }

    public record Decoded(
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredDependencyConstraints> constraints,
            Optional<AuthoredDependencyPolicy> policy) {
    }
}
