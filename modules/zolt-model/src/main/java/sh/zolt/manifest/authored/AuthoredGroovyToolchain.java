package sh.zolt.manifest.authored;

import java.util.Objects;
import sh.zolt.project.toolchain.GroovyToolchainVersion;

/** Explicit Groovy compiler toolchain request before workspace inheritance. */
public record AuthoredGroovyToolchain(GroovyToolchainVersion version) {
    public AuthoredGroovyToolchain {
        version = Objects.requireNonNull(version, "Authored Groovy toolchain version is required.");
    }
}
