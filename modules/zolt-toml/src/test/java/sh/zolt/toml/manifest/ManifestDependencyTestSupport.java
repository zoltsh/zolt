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
        return decodeDependencies(source, ignored -> {});
    }

    public static Decoded decodeDependencies(
            String source, Consumer<Decoded> observer) {
        ManifestDependencyDecoder.DependencyPresenceObserver adapted =
                observer == null ? null : decoded -> observer.accept(project(decoded));
        return project(new ManifestDependencyDecoder().decode(
                ManifestSemanticTestSupport.index(source), adapted));
    }

    public static void decodeDependenciesWithNullIndex() {
        new ManifestDependencyDecoder().decode(null, ignored -> {});
    }

    public static void decodeDependenciesWithNullObserver() {
        new ManifestDependencyDecoder().decode(ManifestSemanticTestSupport.index(""), null);
    }

    public static void constructDependencyDomainsWithNullDependencies() {
        new ManifestDependencyDecoder.Decoded(null, Optional.empty(), Optional.empty());
    }

    public static void constructDependencyDomainsWithNullConstraints() {
        new ManifestDependencyDecoder.Decoded(Optional.empty(), null, Optional.empty());
    }

    public static void constructDependencyDomainsWithNullPolicy() {
        new ManifestDependencyDecoder.Decoded(Optional.empty(), Optional.empty(), null);
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

    private static Decoded project(ManifestDependencyDecoder.Decoded decoded) {
        return new Decoded(
                decoded.dependencies(), decoded.constraints(), decoded.policy());
    }
}
