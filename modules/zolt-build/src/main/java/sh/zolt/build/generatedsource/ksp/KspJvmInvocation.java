package sh.zolt.build.generatedsource.ksp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable inputs for one conservative KSP2 JVM source-generation invocation. */
record KspJvmInvocation(
        Path javaExecutable,
        Path jdkHome,
        List<Path> engineClasspath,
        List<Path> processorClasspath,
        List<Path> kotlinSourceRoots,
        List<Path> javaSourceRoots,
        List<Path> libraries,
        List<Path> friends,
        Path projectBaseDirectory,
        Path outputBaseDirectory,
        Path cachesDirectory,
        Path classOutputDirectory,
        Path kotlinOutputDirectory,
        Path javaOutputDirectory,
        Path resourceOutputDirectory,
        String jvmTarget,
        String moduleName,
        String languageVersion,
        String apiVersion,
        String jvmDefaultMode,
        boolean warningsAsErrors,
        boolean mapAnnotationArgumentsInJava,
        Map<String, String> processorOptions) {
    KspJvmInvocation {
        javaExecutable = path(javaExecutable, "Java executable");
        jdkHome = path(jdkHome, "JDK home");
        engineClasspath = paths(engineClasspath, "KSP engine classpath", true);
        processorClasspath = paths(processorClasspath, "KSP processor classpath", true);
        kotlinSourceRoots = paths(kotlinSourceRoots, "Kotlin source roots", true);
        javaSourceRoots = paths(javaSourceRoots, "Java source roots", false);
        libraries = paths(libraries, "KSP libraries", false);
        friends = paths(friends, "KSP friend paths", false);
        projectBaseDirectory = path(projectBaseDirectory, "Project base directory");
        outputBaseDirectory = path(outputBaseDirectory, "KSP output base directory");
        cachesDirectory = path(cachesDirectory, "KSP caches directory");
        classOutputDirectory = path(classOutputDirectory, "KSP class output directory");
        kotlinOutputDirectory = path(kotlinOutputDirectory, "KSP Kotlin output directory");
        javaOutputDirectory = path(javaOutputDirectory, "KSP Java output directory");
        resourceOutputDirectory = path(resourceOutputDirectory, "KSP resource output directory");
        jvmTarget = text(jvmTarget, "KSP JVM target");
        moduleName = text(moduleName, "KSP module name");
        languageVersion = text(languageVersion, "KSP language version");
        apiVersion = text(apiVersion, "KSP API version");
        jvmDefaultMode = optionalText(jvmDefaultMode);
        processorOptions = processorOptions == null ? Map.of() : Map.copyOf(processorOptions);
        processorOptions.forEach((key, value) -> {
            text(key, "KSP processor option name");
            Objects.requireNonNull(value, "KSP processor option value must not be null.");
        });
    }

    private static Path path(Path value, String label) {
        return Objects.requireNonNull(value, label + " must not be null.")
                .toAbsolutePath()
                .normalize();
    }

    private static List<Path> paths(List<Path> values, String label, boolean required) {
        Objects.requireNonNull(values, label + " must not be null.");
        List<Path> normalized = values.stream().map(value -> path(value, label + " entry")).toList();
        if (required && normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty.");
        }
        if (normalized.stream().distinct().count() != normalized.size()) {
            throw new IllegalArgumentException(label + " must not contain duplicate paths.");
        }
        return normalized;
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must be a non-empty string.");
        }
        return value;
    }

    private static String optionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
