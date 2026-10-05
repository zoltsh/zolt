package sh.zolt.build.compile;

import java.util.List;
import sh.zolt.build.KotlinCompileException;

/** Normalizes caller-provided values used by a Kotlin compiler invocation. */
final class KotlinCompilerOptionValues {
    private KotlinCompilerOptionValues() {
    }

    static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new KotlinCompileException("Kotlin compilation requires a " + label + ".");
        }
        return value.strip();
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    static List<String> copy(List<String> values, String label) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> require(value, label))
                .toList();
    }
}
