package sh.zolt.build.compile.kotlin.kapt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.KotlinCompilationScope;

/** Validated, deterministic {@code -Akey=value} options for one KAPT invocation. */
public record KotlinAnnotationProcessorOptions(Map<String, String> values) {
    private static final Pattern KEY = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    public KotlinAnnotationProcessorOptions {
        Objects.requireNonNull(values, "KAPT annotation processor options are required.");
        TreeMap<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> {
            requireKey(key, "KAPT annotation processor option");
            if (value == null) {
                throw new KotlinCompileException(
                        "KAPT annotation processor option `" + key + "` must have a value.");
            }
            sorted.put(key, value);
        });
        values = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public static KotlinAnnotationProcessorOptions parse(
            List<String> arguments,
            KotlinCompilationScope scope) {
        Objects.requireNonNull(arguments, "Kotlin compiler arguments are required.");
        KotlinCompilationScope compilationScope = Objects.requireNonNull(
                scope,
                "Kotlin compilation scope is required.");
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String argument : arguments) {
            if (!argument.startsWith("-A")) {
                continue;
            }
            int separator = argument.indexOf('=', 2);
            if (separator < 0) {
                throw invalid(
                        compilationScope,
                        argument,
                        "Use `-Akey=value`; valueless annotation processor options are not supported.");
            }
            String key = argument.substring(2, separator);
            requireKey(key, argumentsPath(compilationScope) + " argument `" + argument + "`");
            if (parsed.putIfAbsent(key, argument.substring(separator + 1)) != null) {
                throw invalid(
                        compilationScope,
                        argument,
                        "Configure each annotation processor option key at most once.");
            }
        }
        return new KotlinAnnotationProcessorOptions(parsed);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Encodes the map using the wire format read by Kotlin's KAPT command-line plugin. */
    public String encoded() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeInt(values.size());
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    output.writeUTF(entry.getKey());
                    output.writeUTF(entry.getValue());
                }
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not encode KAPT annotation processor options. Keep each option key and value"
                            + " below the modified UTF-8 limit and try again.",
                    exception);
        }
    }

    private static void requireKey(String key, String label) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new KotlinCompileException(
                    label + " must use a dot-separated Java identifier key.");
        }
    }

    private static KotlinCompileException invalid(
            KotlinCompilationScope scope,
            String argument,
            String remediation) {
        return new KotlinCompileException(
                "Kotlin " + scope.label() + " compilation is not supported when "
                        + argumentsPath(scope) + " contains invalid annotation processor option `"
                        + argument + "`. " + remediation);
    }

    private static String argumentsPath(KotlinCompilationScope scope) {
        return scope == KotlinCompilationScope.MAIN
                ? "[compiler].args"
                : "[compiler.test].args";
    }
}
