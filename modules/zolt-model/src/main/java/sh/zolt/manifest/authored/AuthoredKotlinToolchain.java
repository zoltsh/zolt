package sh.zolt.manifest.authored;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;
import sh.zolt.project.toolchain.KotlinToolchainVersion;

/** Explicit Kotlin compiler toolchain request before workspace inheritance. */
public record AuthoredKotlinToolchain(
        KotlinToolchainVersion version,
        Set<KotlinCompilerPlugin> plugins) {
    public AuthoredKotlinToolchain {
        version = Objects.requireNonNull(version, "Authored Kotlin toolchain version is required.");
        Objects.requireNonNull(plugins, "Authored Kotlin compiler plugins are required.");
        plugins = plugins.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(plugins));
    }

    public AuthoredKotlinToolchain(KotlinToolchainVersion version) {
        this(version, Set.of());
    }
}
