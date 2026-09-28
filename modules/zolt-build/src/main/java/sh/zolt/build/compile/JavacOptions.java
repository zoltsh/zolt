package sh.zolt.build.compile;

import sh.zolt.build.JavacException;
import java.nio.file.Path;
import java.util.List;

public record JavacOptions(
        String release,
        String encoding,
        List<String> arguments,
        List<Path> modulePath,
        boolean hostPlatformApi,
        boolean useJdkRelease) {
    public JavacOptions {
        release = normalize(release);
        encoding = normalize(encoding);
        arguments = copyArguments(arguments);
        modulePath = copyModulePath(modulePath);
        if (hostPlatformApi && useJdkRelease) {
            throw new JavacException(
                    "Host platform API mode cannot be combined with javac --release mode.");
        }
    }

    public JavacOptions(String release, String encoding, List<String> arguments) {
        this(release, encoding, arguments, List.of(), false, true);
    }

    public JavacOptions(
            String release,
            String encoding,
            List<String> arguments,
            List<Path> modulePath,
            boolean hostPlatformApi) {
        this(release, encoding, arguments, modulePath, hostPlatformApi, !hostPlatformApi);
    }

    public static JavacOptions empty() {
        return new JavacOptions("", "", List.of(), List.of(), false, true);
    }

    public JavacOptions withModulePath(List<Path> modulePath) {
        return new JavacOptions(
                release, encoding, arguments, modulePath, hostPlatformApi, useJdkRelease);
    }

    public JavacOptions withHostPlatformApi(boolean hostPlatformApi) {
        return new JavacOptions(
                release, encoding, arguments, modulePath, hostPlatformApi, !hostPlatformApi);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private static List<String> copyArguments(List<String> values) {
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new JavacException("Invalid compiler argument. Use non-empty strings in [compiler].args and [compiler].testArgs.");
            }
        }
        return List.copyOf(values);
    }

    private static List<Path> copyModulePath(List<Path> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
