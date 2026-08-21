package sh.zolt.toml.manifest;

import java.util.Optional;
import java.util.function.Consumer;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicensePolicy;

/** Cross-package test seam for package-private final-manifest dependency decoders. */
public final class ManifestDependencyTestSupport {
    private ManifestDependencyTestSupport() {}

    public static Decoded decodeDependencies(String source) {
        ManifestDependencyDecoder.Decoded decoded = new ManifestDependencyDecoder().decode(
                ManifestSemanticTestSupport.index(source));
        return new Decoded(
                decoded.dependencies(), decoded.constraints(), decoded.policy());
    }

    public static Optional<AuthoredDependencyPolicy> decodePolicy(
            String source, Consumer<AuthoredDependencyPolicy> observer) {
        ManifestDependencyPolicyDecoder.PolicyPresenceObserver adapted =
                observer == null ? null : observer::accept;
        return new ManifestDependencyPolicyDecoder()
                .decode(ManifestSemanticTestSupport.index(source), adapted);
    }

    public static Optional<AuthoredLicensePolicy> decodeLicensePolicy(
            String source, Consumer<AuthoredLicensePolicy> observer) {
        ManifestLicensePolicyDecoder.LicensePolicyPresenceObserver adapted =
                observer == null ? null : observer::accept;
        return new ManifestLicensePolicyDecoder()
                .decode(ManifestSemanticTestSupport.index(source), adapted);
    }

    public record Decoded(
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredDependencyConstraints> constraints,
            Optional<AuthoredDependencyPolicy> policy) {}
}
