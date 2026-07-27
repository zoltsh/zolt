package sh.zolt.doctor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdkDetector implements JdkChecker {
    private static final Pattern VERSION_PATTERN = Pattern.compile("version \"([^\"]+)\"");
    private static final String RELEASE_VERSION_PREFIX = "JAVA_VERSION=";

    private final Function<String, String> environment;
    private final String pathSeparator;
    private final String osName;
    private final Optional<Path> runtimeJavaHome;
    private final ToolVersionReader versionReader;
    private volatile Toolchain toolchain;

    public JdkDetector() {
        this(
                System::getenv,
                java.io.File.pathSeparator,
                System.getProperty("os.name"),
                runtimeJavaHome(System.getProperty("java.home")),
                JdkDetector::readJavaVersion);
    }

    JdkDetector(
            Function<String, String> environment,
            String pathSeparator,
            String osName,
            Optional<Path> runtimeJavaHome,
            ToolVersionReader versionReader) {
        this.environment = environment;
        this.pathSeparator = pathSeparator;
        this.osName = osName;
        this.runtimeJavaHome = runtimeJavaHome == null ? Optional.empty() : runtimeJavaHome;
        this.versionReader = versionReader;
    }

    @Override
    public JdkStatus detect(String requiredVersion) {
        Toolchain detected = toolchain();
        return new JdkStatus(
                detected.javaHome(),
                detected.java(),
                detected.javac(),
                detected.jar(),
                detected.version(),
                requiredVersion);
    }

    private Toolchain toolchain() {
        Toolchain detected = toolchain;
        if (detected != null) {
            return detected;
        }
        synchronized (this) {
            if (toolchain == null) {
                Optional<Path> javaHome = value("JAVA_HOME").map(Path::of);
                Optional<Path> java = findTool("java", javaHome);
                Optional<Path> javac = findTool("javac", javaHome);
                Optional<Path> jar = findTool("jar", javaHome);
                Optional<String> version = java
                        .flatMap(this::readVersion)
                        .flatMap(JdkDetector::majorVersion);
                toolchain = new Toolchain(javaHome, java, javac, jar, version);
            }
            return toolchain;
        }
    }

    private Optional<String> readVersion(Path java) {
        return javaHome(java)
                .flatMap(JdkDetector::readReleaseVersion)
                .or(() -> versionReader.read(java));
    }

    private static Optional<Path> javaHome(Path java) {
        Path bin = java.toAbsolutePath().normalize().getParent();
        return bin == null ? Optional.empty() : Optional.ofNullable(bin.getParent());
    }

    static Optional<String> readReleaseVersion(Path javaHome) {
        Path release = javaHome.resolve("release");
        if (!Files.isRegularFile(release)) {
            return Optional.empty();
        }
        try {
            return Files.readAllLines(release, StandardCharsets.UTF_8).stream()
                    .filter(line -> line.startsWith(RELEASE_VERSION_PREFIX))
                    .map(line -> unquote(line.substring(RELEASE_VERSION_PREFIX.length()).strip()))
                    .filter(value -> !value.isBlank())
                    .findFirst();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    static Optional<String> majorVersion(String versionOutput) {
        Matcher matcher = VERSION_PATTERN.matcher(versionOutput);
        String rawVersion = matcher.find() ? matcher.group(1) : versionOutput.strip();
        if (rawVersion.isBlank()) {
            return Optional.empty();
        }
        String[] parts = rawVersion.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return Optional.of(parts[1]);
        }
        return Optional.of(parts[0]);
    }

    static Optional<Path> runtimeJavaHome(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    private Optional<Path> findTool(String name, Optional<Path> javaHome) {
        String executable = executableName(name);
        if (javaHome.isPresent()) {
            Path candidate = javaHome.orElseThrow().resolve("bin").resolve(executable);
            if (isUsable(candidate)) {
                return Optional.of(candidate);
            }
        }
        if (runtimeJavaHome.isPresent()) {
            Path candidate = runtimeJavaHome.orElseThrow().resolve("bin").resolve(executable);
            if (isUsable(candidate)) {
                return Optional.of(candidate);
            }
        }

        return value("PATH").flatMap(path -> {
            for (String entry : path.split(Pattern.quote(pathSeparator))) {
                if (entry.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(entry).resolve(executable);
                if (isUsable(candidate)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        });
    }

    private Optional<String> value(String key) {
        String value = environment.apply(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private boolean isUsable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private String executableName(String name) {
        if (osName.toLowerCase(Locale.ROOT).contains("win")) {
            return name + ".exe";
        }
        return name;
    }

    private static Optional<String> readJavaVersion(Path java) {
        try {
            Process process = new ProcessBuilder(java.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return exitCode == 0 ? Optional.of(output) : Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    @FunctionalInterface
    interface ToolVersionReader {
        Optional<String> read(Path java);
    }

    private record Toolchain(
            Optional<Path> javaHome,
            Optional<Path> java,
            Optional<Path> javac,
            Optional<Path> jar,
            Optional<String> version) {
        private Toolchain {
            javaHome = javaHome == null ? Optional.empty() : javaHome;
            java = java == null ? Optional.empty() : java;
            javac = javac == null ? Optional.empty() : javac;
            jar = jar == null ? Optional.empty() : jar;
            version = version == null ? Optional.empty() : version;
        }
    }
}
