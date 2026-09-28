package sh.zolt.manifest.effective;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.project.toolchain.GroovyToolchainVersion;

/** Effective Zolt and compiler/runtime requests for one project. */
public record EffectiveToolchains(
        Optional<EffectiveValue<ZoltVersionPin>> zolt,
        Optional<EffectiveJavaRuntime> mainJava,
        Optional<EffectiveTestJavaRuntime> testJava,
        Optional<EffectiveValue<GroovyToolchainVersion>> groovy) {
    public EffectiveToolchains {
        zolt = Objects.requireNonNull(zolt, "Effective Zolt toolchain must not be null.");
        mainJava = Objects.requireNonNull(mainJava, "Effective main Java runtime must not be null.");
        testJava = Objects.requireNonNull(testJava, "Effective test Java runtime must not be null.");
        groovy = Objects.requireNonNull(groovy, "Effective Groovy toolchain must not be null.");
        if (mainJava.isPresent() != testJava.isPresent()) {
            throw new IllegalArgumentException(
                    "Effective main and test Java runtimes must both be present or both be absent.");
        }
        if (mainJava.isEmpty() && groovy.isPresent()) {
            throw new IllegalArgumentException(
                    "An effective Groovy toolchain requires an effective Java runtime.");
        }
        zolt.ifPresent(value -> rejectBuiltIn(value, "Effective Zolt pin"));
        groovy.ifPresent(value -> rejectBuiltIn(value, "Effective Groovy toolchain version"));
        if (mainJava.isPresent()
                && testJava.orElseThrow() instanceof EffectiveTestJavaRuntime.SameAsMain same
                && !same.main().equals(mainJava.orElseThrow())) {
            throw new IllegalArgumentException(
                    "A same-as-main test runtime must contain the effective main Java runtime.");
        }
    }

    /** Compatibility constructor for callers that predate the Groovy toolchain domain. */
    public EffectiveToolchains(
            Optional<EffectiveValue<ZoltVersionPin>> zolt,
            Optional<EffectiveJavaRuntime> mainJava,
            Optional<EffectiveTestJavaRuntime> testJava) {
        this(zolt, mainJava, testJava, Optional.empty());
    }

    /** A project such as a BOM that does not consume a Java runtime. */
    public static EffectiveToolchains withoutJava(
            Optional<EffectiveValue<ZoltVersionPin>> zolt) {
        return new EffectiveToolchains(
                zolt, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static void rejectBuiltIn(EffectiveValue<?> value, String label) {
        if (value.origin() == ValueOrigin.BUILT_IN) {
            throw new IllegalArgumentException(label + " must be authored or inherited.");
        }
    }
}
