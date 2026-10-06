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

    private KotlinToolchainResolutionInputs() {
    }

    static void contribute(List<String> inputs, CompilerSettings settings) {
        input(inputs, COMPILER, settings.kotlinVersion());
        for (KotlinCompilerPlugin plugin : settings.kotlinPlugins()) {
            input(inputs, coordinate(plugin), settings.kotlinVersion());
        }
    }

    private static String coordinate(KotlinCompilerPlugin plugin) {
        return switch (plugin) {
            case SERIALIZATION -> SERIALIZATION;
            case SPRING -> ALL_OPEN;
        };
    }

    private static void input(
            List<String> inputs,
            String coordinate,
            String version) {
        ProjectResolutionFingerprint.compilerToolchainInput(
                inputs, CATEGORY, coordinate, version);
    }
}
