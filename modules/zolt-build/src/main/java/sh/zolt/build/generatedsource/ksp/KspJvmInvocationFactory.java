package sh.zolt.build.generatedsource.ksp;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.BuildException;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.KspGenerationSettings;

/** Maps effective Kotlin build policy onto one standalone KSP2 JVM invocation. */
final class KspJvmInvocationFactory {
    private KspJvmInvocationFactory() {
    }

    static KspJvmInvocation create(
            Path projectRoot,
            KspOutputLayout output,
            JdkStatus jdkStatus,
            KspJvmToolchain toolchain,
            KotlinCompilerOptions compilerOptions,
            List<Path> kotlinSourceRoots,
            List<Path> javaSourceRoots,
            List<Path> libraries,
            KspGenerationSettings generationSettings) {
        Objects.requireNonNull(projectRoot, "Project root is required.");
        KspOutputLayout layout = Objects.requireNonNull(output, "KSP output layout is required.");
        JdkStatus jdk = Objects.requireNonNull(jdkStatus, "Selected JDK status is required.");
        KspJvmToolchain selected = Objects.requireNonNull(
                toolchain,
                "KSP toolchain is required.");
        KotlinCompilerOptions options = Objects.requireNonNull(
                compilerOptions,
                "Kotlin compiler options are required.");
        KspGenerationSettings generation = Objects.requireNonNull(
                generationSettings,
                "KSP generation settings are required.");
        requireMatchingToolchain(generation, selected);
        Path java = jdk.java().orElseThrow(() -> unsupported(
                "the selected JDK has no Java executable",
                "Install a complete JDK or repair the configured Java toolchain."));
        Path javaHome = jdk.javaHome().orElseThrow(() -> unsupported(
                "the selected JDK has no Java home",
                "Install a complete JDK or repair the configured Java toolchain."));
        int target = featureVersion(options.release());
        int jdkFeature = jdk.featureVersion().orElseThrow(() -> unsupported(
                "the selected JDK feature version could not be determined",
                "Use a JDK whose `java -version` output Zolt can read."));
        requirePlatformCompatibility(options, target, jdkFeature);

        String defaultLanguage = compilerLanguageVersion(selected.kotlinVersion());
        String language = options.languageVersion().isEmpty()
                ? defaultLanguage
                : options.languageVersion();
        String api = options.apiVersion().isEmpty() ? language : options.apiVersion();
        List<Path> friends = options.friendPath() == null
                ? List.of()
                : List.of(options.friendPath());
        return new KspJvmInvocation(
                java,
                javaHome,
                selected.engineClasspath(),
                selected.processorClasspath(),
                requiredPaths(kotlinSourceRoots, "Kotlin source roots"),
                optionalPaths(javaSourceRoots, "Java source roots"),
                optionalPaths(libraries, "KSP libraries"),
                friends,
                projectRoot,
                layout.baseDirectory(),
                layout.cachesDirectory(),
                layout.classOutputDirectory(),
                layout.kotlinOutputDirectory(),
                layout.javaOutputDirectory(),
                layout.resourceOutputDirectory(),
                target == 8 ? "1.8" : Integer.toString(target),
                options.moduleName(),
                language,
                api,
                options.jvmDefaultMode(),
                options.warningsAsErrors(),
                false,
                generation.options());
    }

    private static void requireMatchingToolchain(
            KspGenerationSettings generation,
            KspJvmToolchain toolchain) {
        if (!generation.configured()) {
            throw unsupported(
                    "the KSP generation settings are incomplete",
                    "Configure a locked KSP tool and at least one processor, then run `zolt resolve`.");
        }
        String configured = generation.version().orElseThrow();
        if (!configured.equals(toolchain.version())) {
            throw unsupported(
                    "configured KSP version `" + configured
                            + "` does not match resolved toolchain `" + toolchain.version() + "`",
                    "Run `zolt resolve` to refresh the isolated KSP toolchain, then retry.");
        }
    }

    private static void requirePlatformCompatibility(
            KotlinCompilerOptions options,
            int target,
            int jdkFeature) {
        if (options.jvmPreview()) {
            throw unsupported(
                    "JVM preview compilation is enabled",
                    "Remove `-Xjvm-enable-preview` while using KSP; standalone KSP preview"
                            + " attribution is not yet qualified.");
        }
        if (options.useJdkRelease() && target != jdkFeature) {
            throw unsupported(
                    "Kotlin targets Java " + target + " with a Java " + jdkFeature
                            + " build JDK, which requires `-Xjdk-release`",
                    "Select a Java " + target + " build JDK for KSP, or explicitly enable the"
                            + " host platform API mode for this compilation scope.");
        }
    }

    private static int featureVersion(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("1.")) {
            normalized = normalized.substring(2);
        }
        int end = 0;
        while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
            end++;
        }
        try {
            int feature = end == 0 ? -1 : Integer.parseInt(normalized.substring(0, end));
            if (feature >= 8) {
                return feature;
            }
        } catch (NumberFormatException ignored) {
            // The actionable failure below covers malformed and out-of-range values alike.
        }
        throw unsupported(
                "the effective Java release `" + value + "` is not a supported feature version",
                "Set [project].java to Java 8 or newer.");
    }

    private static String compilerLanguageVersion(String kotlinVersion) {
        String[] components = kotlinVersion.split("\\.", 3);
        if (components.length < 2 || !digits(components[0]) || !digits(components[1])) {
            throw unsupported(
                    "Kotlin compiler version `" + kotlinVersion
                            + "` has no stable major.minor language version",
                    "Use a Kotlin toolchain version beginning with `<major>.<minor>`.");
        }
        return components[0] + "." + components[1];
    }

    private static boolean digits(String value) {
        return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
    }

    private static List<Path> requiredPaths(List<Path> paths, String label) {
        List<Path> values = optionalPaths(paths, label);
        if (values.isEmpty()) {
            throw unsupported(
                    label + " are empty",
                    "Add Kotlin sources to the KSP compilation scope or remove the KSP step.");
        }
        return values;
    }

    private static List<Path> optionalPaths(List<Path> paths, String label) {
        return List.copyOf(Objects.requireNonNull(paths, label + " must not be null."));
    }

    private static BuildException unsupported(String reason, String remediation) {
        return BuildException.actionable(
                "KSP generation cannot start because " + reason + ".",
                remediation);
    }
}
