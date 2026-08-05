package sh.zolt.toolchain.jvm;

import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class AmbientJavaToolchainProbe implements JavaToolchainProbe {
    private final Function<String, String> environment;
    private final String pathSeparator;
    private final String osName;
    private final Optional<Path> runtimeJavaHome;
    private final RuntimeInfoReader runtimeInfoReader;
    private volatile AmbientTools cachedTools;

    public AmbientJavaToolchainProbe() {
        this(
                System::getenv,
                java.io.File.pathSeparator,
                System.getProperty("os.name"),
                runtimeJavaHome(System.getProperty("java.home")),
                JavaRuntimeProbe::read);
    }

    AmbientJavaToolchainProbe(
            Function<String, String> environment,
            String pathSeparator,
            String osName,
            Optional<Path> runtimeJavaHome,
            RuntimeInfoReader runtimeInfoReader) {
        this.environment = environment;
        this.pathSeparator = pathSeparator;
        this.osName = osName;
        this.runtimeJavaHome = runtimeJavaHome == null ? Optional.empty() : runtimeJavaHome;
        this.runtimeInfoReader = runtimeInfoReader;
    }

    @Override
    public ResolvedJavaToolchain resolve(JavaToolchainRequest request) {
        AmbientTools tools = ambientTools();
        List<String> problems = problems(
                request,
                tools.javaHome(),
                tools.java(),
                tools.javac(),
                tools.jar(),
                tools.nativeImage(),
                tools.runtime());
        List<String> notes = notes(request);
        return new ResolvedJavaToolchain(
                JavaToolchainSource.AMBIENT,
                tools.javaHome(),
                tools.java(),
                tools.javac(),
                tools.jar(),
                tools.nativeImage(),
                tools.runtime(),
                request,
                problems,
                notes);
    }

    private AmbientTools ambientTools() {
        AmbientTools tools = cachedTools;
        if (tools != null) {
            return tools;
        }
        synchronized (this) {
            if (cachedTools == null) {
                cachedTools = detectAmbientTools();
            }
            return cachedTools;
        }
    }

    private AmbientTools detectAmbientTools() {
        Optional<Path> configuredJavaHome = value("JAVA_HOME").map(Path::of);
        Optional<Path> java = findTool("java", configuredJavaHome);
        Optional<JavaRuntimeProbe.Result> probe = java.flatMap(runtimeInfoReader::read);
        Optional<Path> javaHome = selectedJavaHome(
                configuredJavaHome,
                java,
                probe.flatMap(JavaRuntimeProbe.Result::javaHome));
        return new AmbientTools(
                javaHome,
                java,
                findTool("javac", configuredJavaHome, javaHome),
                findTool("jar", configuredJavaHome, javaHome),
                findTool("native-image", configuredJavaHome, javaHome),
                probe.map(JavaRuntimeProbe.Result::runtime).orElse(JavaRuntimeInfo.empty()));
    }

    static Optional<Path> runtimeJavaHome(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    private Optional<Path> findTool(String name, Optional<Path> configuredJavaHome) {
        return findTool(name, configuredJavaHome, Optional.empty());
    }

    private Optional<Path> findTool(
            String name,
            Optional<Path> configuredJavaHome,
            Optional<Path> resolvedJavaHome) {
        String executable = executableName(name);
        Optional<Path> fromConfigured = configuredJavaHome
                .map(home -> home.resolve("bin").resolve(executable))
                .filter(this::isUsable);
        if (fromConfigured.isPresent()) {
            return fromConfigured;
        }
        Optional<Path> fromRuntime = runtimeJavaHome
                .map(home -> home.resolve("bin").resolve(executable))
                .filter(this::isUsable);
        if (fromRuntime.isPresent()) {
            return fromRuntime;
        }
        Optional<Path> fromPath = value("PATH").flatMap(path -> {
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
        if (fromPath.isPresent()) {
            return fromPath;
        }
        return resolvedJavaHome
                .map(home -> home.resolve("bin").resolve(executable))
                .filter(this::isUsable);
    }

    private Optional<Path> selectedJavaHome(
            Optional<Path> configuredJavaHome,
            Optional<Path> java,
            Optional<Path> reportedJavaHome) {
        if (configuredJavaHome.isPresent()) {
            return configuredJavaHome;
        }
        if (runtimeJavaHome.isPresent()
                && java.map(path -> path.toAbsolutePath().normalize().startsWith(runtimeJavaHome.orElseThrow()))
                        .orElse(false)) {
            return runtimeJavaHome;
        }
        Optional<Path> reported = reportedJavaHome.filter(Files::isDirectory);
        if (reported.isPresent()) {
            return reported;
        }
        return java.flatMap(AmbientJavaToolchainProbe::inferJavaHome);
    }

    private List<String> problems(
            JavaToolchainRequest request,
            Optional<Path> javaHome,
            Optional<Path> java,
            Optional<Path> javac,
            Optional<Path> jar,
            Optional<Path> nativeImage,
            JavaRuntimeInfo runtime) {
        List<String> problems = new ArrayList<>();
        if (request.policy() == ToolchainPolicy.REQUIRE_MANAGED) {
            problems.add("This project requires a Zolt-managed Java toolchain, but ambient Java was selected. Run `zolt toolchain status` for details, then `zolt toolchain sync`.");
        }
        if (java.isEmpty()) {
            problems.add("Missing `java`. Install a JDK, set JAVA_HOME, or configure [toolchain.java] and run `zolt toolchain sync`.");
        }
        if (javac.isEmpty()) {
            problems.add("Missing `javac`. Install a JDK, set JAVA_HOME, or configure [toolchain.java] and run `zolt toolchain sync`.");
        }
        if (jar.isEmpty()) {
            problems.add("Missing `jar`. Install a JDK, set JAVA_HOME, or configure [toolchain.java] and run `zolt toolchain sync`.");
        }
        if (java.isPresent() && runtime.featureVersion().isEmpty()) {
            problems.add("Could not determine Java version. Check that `java -version` runs successfully.");
        }
        if (runtime.featureVersion().isPresent() && !versionSatisfies(runtime.featureVersion().orElseThrow(), request.version())) {
            problems.add("Java version mismatch. Project requests "
                    + request.version()
                    + " or newer but detected "
                    + runtime.featureVersion().orElseThrow()
                    + ".");
        }
        if (java.isPresent() && javaHome.isEmpty()) {
            problems.add("Could not determine the Java home for `"
                    + java.orElseThrow()
                    + "`. Set JAVA_HOME to a JDK directory, or configure [toolchain.java] and run `zolt toolchain sync`.");
        }
        if (request.requiresNativeImage() && nativeImage.isEmpty()) {
            problems.add("Native Image is missing from the resolved Java toolchain. Run `zolt toolchain status`, then `zolt toolchain sync`, or pass --native-image as an explicit override.");
        }
        return List.copyOf(problems);
    }

    private static List<String> notes(JavaToolchainRequest request) {
        if (request.distribution().isPresent()) {
            return List.of("Distribution matching is enforced only for managed toolchains; ambient fallback checks Java version and requested tools.");
        }
        return List.of();
    }

    private static boolean versionSatisfies(String detected, String requested) {
        Optional<Integer> detectedFeature = integerFeature(detected);
        Optional<Integer> requestedFeature = integerFeature(requested);
        if (detectedFeature.isPresent() && requestedFeature.isPresent()) {
            return detectedFeature.orElseThrow() >= requestedFeature.orElseThrow();
        }
        return detected.equals(requested);
    }

    private static Optional<Integer> integerFeature(String value) {
        Optional<String> feature = JavaRuntimeProbe.featureVersion(value);
        if (feature.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(feature.orElseThrow()));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
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

    private static Optional<Path> inferJavaHome(Path java) {
        Path bin = java.toAbsolutePath().normalize().getParent();
        if (bin == null || !"bin".equals(bin.getFileName().toString())) {
            return Optional.empty();
        }
        return Optional.ofNullable(bin.getParent());
    }

    @FunctionalInterface
    interface RuntimeInfoReader {
        Optional<JavaRuntimeProbe.Result> read(Path java);
    }

    private record AmbientTools(
            Optional<Path> javaHome,
            Optional<Path> java,
            Optional<Path> javac,
            Optional<Path> jar,
            Optional<Path> nativeImage,
            JavaRuntimeInfo runtime) {
        private AmbientTools {
            javaHome = javaHome == null ? Optional.empty() : javaHome;
            java = java == null ? Optional.empty() : java;
            javac = javac == null ? Optional.empty() : javac;
            jar = jar == null ? Optional.empty() : jar;
            nativeImage = nativeImage == null ? Optional.empty() : nativeImage;
            runtime = runtime == null ? JavaRuntimeInfo.empty() : runtime;
        }
    }
}
