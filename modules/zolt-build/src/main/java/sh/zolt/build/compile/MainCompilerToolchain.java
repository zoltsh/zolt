package sh.zolt.build.compile;

import java.util.Objects;
import java.util.Optional;
import sh.zolt.doctor.JdkStatus;

/** The single compiler family selected for the main source set. */
public final class MainCompilerToolchain {
    private static final String KOTLIN_MAIN_SEMANTICS = "kotlin-main-v1";

    private final Language language;
    private final Optional<GroovyCompilerToolchain> groovyToolchain;
    private final Optional<KotlinCompilerToolchain> kotlinToolchain;

    private MainCompilerToolchain(
            Language language,
            Optional<GroovyCompilerToolchain> groovyToolchain,
            Optional<KotlinCompilerToolchain> kotlinToolchain) {
        this.language = Objects.requireNonNull(language, "Main compiler language is required.");
        this.groovyToolchain = Objects.requireNonNull(
                groovyToolchain,
                "Main Groovy compiler selection is required.");
        this.kotlinToolchain = Objects.requireNonNull(
                kotlinToolchain,
                "Main Kotlin compiler selection is required.");
        requireConsistentSelection();
    }

    static MainCompilerToolchain javaOnly() {
        return new MainCompilerToolchain(Language.JAVA, Optional.empty(), Optional.empty());
    }

    static MainCompilerToolchain groovy(GroovyCompilerToolchain toolchain) {
        return new MainCompilerToolchain(
                Language.GROOVY,
                Optional.of(Objects.requireNonNull(toolchain, "Groovy compiler toolchain is required.")),
                Optional.empty());
    }

    static MainCompilerToolchain kotlin(KotlinCompilerToolchain toolchain) {
        return new MainCompilerToolchain(
                Language.KOTLIN,
                Optional.empty(),
                Optional.of(Objects.requireNonNull(toolchain, "Kotlin compiler toolchain is required.")));
    }

    public Language language() {
        return language;
    }

    public Optional<GroovyCompilerToolchain> groovyToolchain() {
        return groovyToolchain;
    }

    public Optional<KotlinCompilerToolchain> kotlinToolchain() {
        return kotlinToolchain;
    }

    public String compilerIdentity(JdkStatus jdkStatus) {
        return switch (language) {
            case JAVA -> EffectiveCompilerIdentity.of(jdkStatus);
            case GROOVY -> EffectiveCompilerIdentity.of(jdkStatus, groovyToolchain.orElseThrow());
            case KOTLIN -> EffectiveCompilerIdentity.of(
                    jdkStatus,
                    "kotlinCompiler",
                    kotlinToolchain.orElseThrow().identity(),
                    KOTLIN_MAIN_SEMANTICS);
        };
    }

    private void requireConsistentSelection() {
        boolean valid = switch (language) {
            case JAVA -> groovyToolchain.isEmpty() && kotlinToolchain.isEmpty();
            case GROOVY -> groovyToolchain.isPresent() && kotlinToolchain.isEmpty();
            case KOTLIN -> groovyToolchain.isEmpty() && kotlinToolchain.isPresent();
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "Main compiler selection must contain exactly the toolchain for its active language.");
        }
    }

    public enum Language {
        JAVA,
        GROOVY,
        KOTLIN
    }
}
