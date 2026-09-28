package sh.zolt.manifest.authored;

import java.util.Objects;
import sh.zolt.project.toolchain.KotlinToolchainVersion;

/** Explicit Kotlin compiler toolchain request before workspace inheritance. */
public record AuthoredKotlinToolchain(KotlinToolchainVersion version) {
    public AuthoredKotlinToolchain {
        version = Objects.requireNonNull(version, "Authored Kotlin toolchain version is required.");
    }
}
