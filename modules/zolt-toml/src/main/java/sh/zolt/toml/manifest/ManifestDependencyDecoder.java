package sh.zolt.toml.manifest;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;

/** Coordinates authored dependency domains without resolving or composing them. */
final class ManifestDependencyDecoder {
    private final ManifestDependenciesDecoder dependencies =
            new ManifestDependenciesDecoder();
    private final ManifestDependencyConstraintsDecoder constraints =
            new ManifestDependencyConstraintsDecoder();
    private final ManifestDependencyPolicyDecoder policy =
            new ManifestDependencyPolicyDecoder();

    Decoded decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Optional<AuthoredDependencies> decodedDependencies =
                dependencies.decode(index, ignored -> {});
        Optional<AuthoredDependencyConstraints> decodedConstraints =
                constraints.decode(index, ignored -> {});
        Optional<AuthoredDependencyPolicy> decodedPolicy =
                policy.decode(index, ignored -> {});
        return new Decoded(decodedDependencies, decodedConstraints, decodedPolicy);
    }

    record Decoded(
            Optional<AuthoredDependencies> dependencies,
            Optional<AuthoredDependencyConstraints> constraints,
            Optional<AuthoredDependencyPolicy> policy) {
        Decoded {
            dependencies = Objects.requireNonNull(
                    dependencies, "Decoded dependencies must not be null.");
            constraints = Objects.requireNonNull(
                    constraints, "Decoded dependency constraints must not be null.");
            policy = Objects.requireNonNull(
                    policy, "Decoded dependency policy must not be null.");
        }
    }
}
