package sh.zolt.manifest.effective;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.manifest.ZoltVersionPin;
import sh.zolt.project.toolchain.GroovyToolchainVersion;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;
import sh.zolt.project.toolchain.KotlinToolchainVersion;

/** Effective Zolt and compiler/runtime requests for one project. */
public record EffectiveToolchains(
        Optional<EffectiveValue<ZoltVersionPin>> zolt,
        Optional<EffectiveJavaRuntime> mainJava,
        Optional<EffectiveTestJavaRuntime> testJava,
        Optional<EffectiveValue<GroovyToolchainVersion>> groovy,
        Optional<EffectiveValue<KotlinToolchainVersion>> kotlin,
        Optional<EffectiveValue<Set<KotlinCompilerPlugin>>> kotlinPlugins) {
    public EffectiveToolchains {
        zolt = Objects.requireNonNull(zolt, "Effective Zolt toolchain must not be null.");
        mainJava = Objects.requireNonNull(mainJava, "Effective main Java runtime must not be null.");
        testJava = Objects.requireNonNull(testJava, "Effective test Java runtime must not be null.");
        groovy = Objects.requireNonNull(groovy, "Effective Groovy toolchain must not be null.");
        kotlin = Objects.requireNonNull(kotlin, "Effective Kotlin toolchain must not be null.");
        kotlinPlugins = Objects.requireNonNull(
                kotlinPlugins, "Effective Kotlin compiler plugins must not be null.");
        if (mainJava.isPresent() != testJava.isPresent()) {
            throw new IllegalArgumentException(
                    "Effective main and test Java runtimes must both be present or both be absent.");
        }
        if (mainJava.isEmpty() && groovy.isPresent()) {
            throw new IllegalArgumentException(
                    "An effective Groovy toolchain requires an effective Java runtime.");
        }
        if (mainJava.isEmpty() && kotlin.isPresent()) {
            throw new IllegalArgumentException(
                    "An effective Kotlin toolchain requires an effective Java runtime.");
        }
        if (kotlin.isPresent() != kotlinPlugins.isPresent()) {
            throw new IllegalArgumentException(
                    "Effective Kotlin version and compiler plugins must both be present or both be absent.");
        }
        zolt.ifPresent(value -> rejectBuiltIn(value, "Effective Zolt pin"));
        groovy.ifPresent(value -> rejectBuiltIn(value, "Effective Groovy toolchain version"));
        kotlin.ifPresent(value -> rejectBuiltIn(value, "Effective Kotlin toolchain version"));
        kotlinPlugins.ifPresent(value -> rejectBuiltIn(value, "Effective Kotlin compiler plugins"));
        if (mainJava.isPresent()
                && testJava.orElseThrow() instanceof EffectiveTestJavaRuntime.SameAsMain same
                && !same.main().equals(mainJava.orElseThrow())) {
            throw new IllegalArgumentException(
                    "A same-as-main test runtime must contain the effective main Java runtime.");
        }
    }

    /** Compatibility constructor for callers that predate Kotlin compiler plugins. */
    public EffectiveToolchains(
            Optional<EffectiveValue<ZoltVersionPin>> zolt,
            Optional<EffectiveJavaRuntime> mainJava,
            Optional<EffectiveTestJavaRuntime> testJava,
            Optional<EffectiveValue<GroovyToolchainVersion>> groovy,
            Optional<EffectiveValue<KotlinToolchainVersion>> kotlin) {
        this(
                zolt,
                mainJava,
                testJava,
                groovy,
                kotlin,
                kotlin.map(value -> value.map(ignored -> Set.of())));
    }

    /** Compatibility constructor for callers that predate the Kotlin toolchain domain. */
    public EffectiveToolchains(
            Optional<EffectiveValue<ZoltVersionPin>> zolt,
            Optional<EffectiveJavaRuntime> mainJava,
            Optional<EffectiveTestJavaRuntime> testJava,
            Optional<EffectiveValue<GroovyToolchainVersion>> groovy) {
        this(zolt, mainJava, testJava, groovy, Optional.empty());
    }

    /** Compatibility constructor for callers that predate the Groovy toolchain domain. */
    public EffectiveToolchains(
            Optional<EffectiveValue<ZoltVersionPin>> zolt,
            Optional<EffectiveJavaRuntime> mainJava,
            Optional<EffectiveTestJavaRuntime> testJava) {
        this(zolt, mainJava, testJava, Optional.empty(), Optional.empty());
    }

    /** A project such as a BOM that does not consume a Java runtime. */
    public static EffectiveToolchains withoutJava(
            Optional<EffectiveValue<ZoltVersionPin>> zolt) {
        return new EffectiveToolchains(
                zolt,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static void rejectBuiltIn(EffectiveValue<?> value, String label) {
        if (value.origin() == ValueOrigin.BUILT_IN) {
            throw new IllegalArgumentException(label + " must be authored or inherited.");
        }
    }
}
