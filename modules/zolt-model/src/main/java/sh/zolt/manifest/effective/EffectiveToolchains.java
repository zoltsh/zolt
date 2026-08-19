package sh.zolt.manifest.effective;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.ZoltVersionPin;

/** Effective Zolt and Java requests for one project. */
public record EffectiveToolchains(
        Optional<EffectiveValue<ZoltVersionPin>> zolt,
        Optional<EffectiveJavaRuntime> mainJava,
        Optional<EffectiveTestJavaRuntime> testJava) {
    public EffectiveToolchains {
        zolt = Objects.requireNonNull(zolt, "Effective Zolt toolchain must not be null.");
        mainJava = Objects.requireNonNull(mainJava, "Effective main Java runtime must not be null.");
        testJava = Objects.requireNonNull(testJava, "Effective test Java runtime must not be null.");
        if (mainJava.isPresent() != testJava.isPresent()) {
            throw new IllegalArgumentException(
                    "Effective main and test Java runtimes must both be present or both be absent.");
        }
        zolt.ifPresent(value -> rejectBuiltIn(value, "Effective Zolt pin"));
        if (mainJava.isPresent()
                && testJava.orElseThrow() instanceof EffectiveTestJavaRuntime.SameAsMain same
                && !same.main().equals(mainJava.orElseThrow())) {
            throw new IllegalArgumentException(
                    "A same-as-main test runtime must contain the effective main Java runtime.");
        }
    }

    /** A project such as a BOM that does not consume a Java runtime. */
    public static EffectiveToolchains withoutJava(
            Optional<EffectiveValue<ZoltVersionPin>> zolt) {
        return new EffectiveToolchains(zolt, Optional.empty(), Optional.empty());
    }

    private static void rejectBuiltIn(EffectiveValue<?> value, String label) {
        if (value.origin() == ValueOrigin.BUILT_IN) {
            throw new IllegalArgumentException(label + " must be authored or inherited.");
        }
    }
}
