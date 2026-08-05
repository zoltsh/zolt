package sh.zolt.toolchain.jvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the runtime facts Zolt needs from a {@code java} executable with a single
 * {@code -XshowSettings:properties -version} process: version and vendor, plus {@code java.home} as
 * the JVM itself reports it. Asking the JVM is the only reliable way to learn the home of a
 * {@code java} that is a wrapper script (jenv, asdf, mise, sdkman), because such a script's
 * filesystem location says nothing about the JDK it dispatches to.
 */
final class JavaRuntimeProbe {
    private static final Pattern VERSION_LINE = Pattern.compile("version \"([^\"]+)\"");
    private static final Pattern PROPERTY_LINE = Pattern.compile("\\s*([^=]+?)\\s*=\\s*(.+)");

    private JavaRuntimeProbe() {
    }

    static Optional<Result> read(Path java) {
        try {
            Process process = new ProcessBuilder(java.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return exitCode == 0 ? Optional.of(parse(output)) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static Result parse(String output) {
        Optional<String> version = versionFromOutput(output);
        JavaRuntimeInfo runtime = new JavaRuntimeInfo(
                version,
                version.flatMap(JavaRuntimeProbe::featureVersion),
                property(output, "java.vendor"));
        return new Result(runtime, javaHome(output));
    }

    static Optional<String> featureVersion(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip();
        String[] parts = normalized.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return Optional.of(parts[1]);
        }
        return Optional.of(parts[0]);
    }

    private static Optional<Path> javaHome(String output) {
        return property(output, "java.home")
                .filter(value -> !value.isBlank())
                .map(Path::of);
    }

    private static Optional<String> versionFromOutput(String output) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        Matcher line = VERSION_LINE.matcher(output);
        if (line.find()) {
            return Optional.of(line.group(1));
        }
        return property(output, "java.version");
    }

    private static Optional<String> property(String output, String key) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }
        for (String line : output.lines().toList()) {
            Matcher matcher = PROPERTY_LINE.matcher(line);
            if (matcher.matches() && key.equals(matcher.group(1).strip())) {
                return Optional.of(matcher.group(2).strip());
            }
        }
        return Optional.empty();
    }

    /** Runtime facts read from one {@code java} probe: version/vendor plus the JVM's own home. */
    record Result(JavaRuntimeInfo runtime, Optional<Path> javaHome) {
        Result {
            runtime = runtime == null ? JavaRuntimeInfo.empty() : runtime;
            javaHome = javaHome == null ? Optional.empty() : javaHome;
        }
    }
}
