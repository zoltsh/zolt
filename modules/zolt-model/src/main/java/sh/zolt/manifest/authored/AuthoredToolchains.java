package sh.zolt.manifest.authored;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ZoltVersionPin;

/** Authored toolchain requests before workspace inheritance and project-derived defaults. */
public record AuthoredToolchains(
        Optional<ZoltVersionPin> zolt,
        Optional<AuthoredJavaToolchain> mainJava,
        Optional<AuthoredJavaTestToolchain> testJava,
        Optional<AuthoredGroovyToolchain> groovy,
        Optional<AuthoredKotlinToolchain> kotlin) {
    public AuthoredToolchains {
        zolt = Objects.requireNonNull(zolt, "Authored Zolt toolchain must not be null.");
        mainJava = Objects.requireNonNull(mainJava, "Authored main Java toolchain must not be null.");
        testJava = Objects.requireNonNull(testJava, "Authored test Java toolchain must not be null.");
        groovy = Objects.requireNonNull(groovy, "Authored Groovy toolchain must not be null.");
        kotlin = Objects.requireNonNull(kotlin, "Authored Kotlin toolchain must not be null.");
    }

    /** Compatibility constructor for callers that predate the Kotlin toolchain domain. */
    public AuthoredToolchains(
            Optional<ZoltVersionPin> zolt,
            Optional<AuthoredJavaToolchain> mainJava,
            Optional<AuthoredJavaTestToolchain> testJava,
            Optional<AuthoredGroovyToolchain> groovy) {
        this(zolt, mainJava, testJava, groovy, Optional.empty());
    }

    /** Compatibility constructor for callers that predate the Groovy toolchain domain. */
    public AuthoredToolchains(
            Optional<ZoltVersionPin> zolt,
            Optional<AuthoredJavaToolchain> mainJava,
            Optional<AuthoredJavaTestToolchain> testJava) {
        this(zolt, mainJava, testJava, Optional.empty(), Optional.empty());
    }

    public static AuthoredToolchains empty() {
        return new AuthoredToolchains(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
