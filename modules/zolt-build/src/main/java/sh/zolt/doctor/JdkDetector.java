package sh.zolt.doctor;

import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
                detected.compilerIdentity().isBlank()
                        ? Optional.empty()
                        : Optional.of(detected.compilerIdentity()),
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
                        .flatMap(JdkDetector::fullVersion);
                toolchain = new Toolchain(
                        javaHome,
                        java,
                        javac,
                        jar,
                        version,
                        compilerIdentity(javaHome, java, javac, version));
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
        Optional<String> fullVersion = fullVersion(versionOutput);
        if (fullVersion.isEmpty()) {
            return Optional.empty();
        }
        String rawVersion = fullVersion.orElseThrow();
        if (rawVersion.isBlank()) {
            return Optional.empty();
        }
        String[] parts = rawVersion.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return Optional.of(parts[1]);
        }
        return Optional.of(parts[0]);
    }

    static Optional<String> fullVersion(String versionOutput) {
        if (versionOutput == null) {
            return Optional.empty();
        }
        Matcher matcher = VERSION_PATTERN.matcher(versionOutput);
        String rawVersion = matcher.find() ? matcher.group(1) : versionOutput.strip();
        return rawVersion.isBlank() ? Optional.empty() : Optional.of(rawVersion);
    }

    private String compilerIdentity(
            Optional<Path> configuredJavaHome,
            Optional<Path> java,
            Optional<Path> javac,
            Optional<String> version) {
        if (javac.isEmpty()) {
            return "";
        }
        Path compiler = javac.orElseThrow().toAbsolutePath().normalize();
        Path detectedJavaHome = java.flatMap(JdkDetector::javaHome)
                .or(() -> configuredJavaHome)
                .map(path -> path.toAbsolutePath().normalize())
                .orElse(null);
        String release = detectedJavaHome == null
                ? "missing"
                : releaseIdentity(detectedJavaHome.resolve("release"));
        return String.join(
                "\n",
                "source=system",
                "version=" + version.orElse("unknown"),
                "javaHome=" + (detectedJavaHome == null ? "missing" : detectedJavaHome),
                "javac=" + compiler,
                "javacSize=" + fileSize(compiler),
                "javacModified=" + lastModified(compiler),
                "release=" + release,
                "os=" + osName,
                "arch=" + System.getProperty("os.arch", "unknown"));
    }

    private static String releaseIdentity(Path release) {
        try {
            return Files.isRegularFile(release)
                    ? Base64.getEncoder().encodeToString(Files.readAllBytes(release))
                    : "missing";
        } catch (IOException exception) {
            return "unreadable";
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return -1L;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return -1L;
        }
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
            try (BuildCancellation.Registration ignored =
                    ProcessCancellation.register(process)) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();
                return exitCode == 0 ? Optional.of(output) : Optional.empty();
            }
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
            Optional<String> version,
            String compilerIdentity) {
        private Toolchain {
            javaHome = javaHome == null ? Optional.empty() : javaHome;
            java = java == null ? Optional.empty() : java;
            javac = javac == null ? Optional.empty() : javac;
            jar = jar == null ? Optional.empty() : jar;
            version = version == null ? Optional.empty() : version;
            compilerIdentity = compilerIdentity == null ? "" : compilerIdentity;
        }
    }
}
