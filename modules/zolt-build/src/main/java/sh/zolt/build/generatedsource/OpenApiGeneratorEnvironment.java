package sh.zolt.build.generatedsource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Builds the curated, never-inherited environment for the OpenAPI generator subprocess.
 *
 * <p>The generator reads several environment variables that make it shell out per generated file
 * ({@code JAVA_POST_PROCESS_FILE} and its per-language siblings). Those commands would run outside
 * process supervision and outside generated-source fingerprinting, so Zolt launches the generator
 * with a cleared environment carrying only OS essentials and {@code JAVA_HOME}; nothing else from
 * Zolt's ambient environment reaches the generator.
 */
final class OpenApiGeneratorEnvironment {
    private OpenApiGeneratorEnvironment() {
    }

    static Map<String, String> build(UnaryOperator<String> ambientEnv) {
        return build(ambientEnv, System.getProperty("os.name", ""));
    }

    static Map<String, String> build(UnaryOperator<String> ambientEnv, String osName) {
        Map<String, String> environment = new LinkedHashMap<>();
        putIfPresent(environment, "PATH", ambientEnv.apply("PATH"));
        putIfPresent(environment, "HOME", ambientEnv.apply("HOME"));
        putIfPresent(environment, "JAVA_HOME", ambientEnv.apply("JAVA_HOME"));
        // Windows resolves environment names case-insensitively, so the curated upper-case PATH above
        // is authoritative regardless of the ambient `Path` casing. A cleared Windows environment must
        // still carry SystemRoot/SystemDrive or the JVM fails to initialize (winsock, crypto, and
        // %TEMP% resolution all read SystemRoot).
        if (osName.toLowerCase(Locale.ROOT).contains("win")) {
            putIfPresent(environment, "SystemRoot", ambientEnv.apply("SystemRoot"));
            putIfPresent(environment, "SystemDrive", ambientEnv.apply("SystemDrive"));
        }
        return Map.copyOf(environment);
    }

    private static void putIfPresent(Map<String, String> environment, String name, String value) {
        if (value != null && !value.isBlank()) {
            environment.put(name, value);
        }
    }
}
