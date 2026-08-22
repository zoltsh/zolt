package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ZoltVersionPin;

/** Authored toolchain requests before workspace inheritance and project-derived defaults. */
public record AuthoredToolchains(
        Optional<ZoltVersionPin> zolt,
        Optional<AuthoredJavaToolchain> mainJava,
        Optional<AuthoredJavaTestToolchain> testJava) {
    public AuthoredToolchains {
        zolt = Objects.requireNonNull(zolt, "Authored Zolt toolchain must not be null.");
        mainJava = Objects.requireNonNull(mainJava, "Authored main Java toolchain must not be null.");
        testJava = Objects.requireNonNull(testJava, "Authored test Java toolchain must not be null.");
    }

    public static AuthoredToolchains empty() {
        return new AuthoredToolchains(Optional.empty(), Optional.empty(), Optional.empty());
    }
}
