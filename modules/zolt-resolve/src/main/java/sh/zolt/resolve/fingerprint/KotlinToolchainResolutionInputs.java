package sh.zolt.resolve.fingerprint;

import java.util.List;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;

/** Emits exact lock identity for Zolt-owned Kotlin compiler tooling. */
final class KotlinToolchainResolutionInputs {
    private static final String CATEGORY = "toolchain.kotlin";
    private static final String COMPILER =
            "org.jetbrains.kotlin:kotlin-compiler-embeddable";
    private static final String SERIALIZATION =
            "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable";
    private static final String ALL_OPEN =
            "org.jetbrains.kotlin:kotlin-allopen-compiler-plugin-embeddable";
    private static final String NO_ARG =
            "org.jetbrains.kotlin:kotlin-noarg-compiler-plugin-embeddable";

    private KotlinToolchainResolutionInputs() {
    }

    static void contribute(List<String> inputs, CompilerSettings settings) {
        input(inputs, COMPILER, settings.kotlinVersion());
        if (settings.kotlinPlugins().contains(KotlinCompilerPlugin.SERIALIZATION)) {
            input(inputs, SERIALIZATION, settings.kotlinVersion());
        }
        if (settings.kotlinPlugins().contains(KotlinCompilerPlugin.SPRING)
                || settings.kotlinPlugins().contains(KotlinCompilerPlugin.MICRONAUT)) {
            input(inputs, ALL_OPEN, settings.kotlinVersion());
        }
        if (settings.kotlinPlugins().contains(KotlinCompilerPlugin.JPA)) {
            input(inputs, NO_ARG, settings.kotlinVersion());
        }
    }

    private static void input(
            List<String> inputs,
            String coordinate,
            String version) {
        ProjectResolutionFingerprint.compilerToolchainInput(
                inputs, CATEGORY, coordinate, version);
    }
}
