package sh.zolt.build.compile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;

/** Shared JVM target and encoding rules for bounded Kotlin source sets. */
public final class KotlinCompileOptionsPolicy {
    private KotlinCompileOptionsPolicy() {
    }

    public static KotlinCompilerRunner.Options options(
            ProjectConfig config,
            JdkStatus jdkStatus,
            KotlinCompilationScope scope) {
        Objects.requireNonNull(config, "Project configuration is required.");
        Objects.requireNonNull(jdkStatus, "Selected JDK status is required.");
        KotlinCompilationScope compilationScope = Objects.requireNonNull(
                scope,
                "Kotlin compilation scope is required.");
        CompilerSettings compiler = config.compilerSettings();
        KotlinCompilerArgumentPolicy.MappedArguments mappedArguments =
                KotlinCompilerArgumentPolicy.map(compiler, compilationScope);
        requireUtf8(compiler.encoding(), compilationScope);
        int release = featureVersion(
                MainCompileOptions.effectiveRelease(config),
                compilationScope);
        int jdkFeature = jdkStatus.featureVersion().orElseThrow(() -> unsupported(
                compilationScope,
                "the selected JDK feature version could not be determined",
                "Use a JDK whose `java -version` output Zolt can read."));
        if (release > jdkFeature) {
            throw unsupported(
                    compilationScope,
                    "the effective Java release " + release
                            + " is newer than the selected JDK feature version " + jdkFeature,
                    "Select a Java " + release + " or newer build JDK, or lower [project].java.");
        }
        if (jdkStatus.java().isEmpty() || jdkStatus.javaHome().isEmpty()) {
            throw unsupported(
                    compilationScope,
                    "the selected JDK has no complete Java runtime home",
                    "Install a complete JDK or repair the configured Java toolchain.");
        }
        boolean hostPlatformApi = compilationScope == KotlinCompilationScope.MAIN
                ? compiler.mainHostPlatformApi()
                : compiler.testHostPlatformApi();
        return new KotlinCompilerRunner.Options(
                Integer.toString(release),
                moduleName(config, compilationScope),
                hostPlatformApi,
                !hostPlatformApi && jdkFeature >= 9,
                mappedArguments.javaParameters(),
                mappedArguments.warningsAsErrors(),
                mappedArguments.suppressWarnings(),
                mappedArguments.extraWarnings(),
                mappedArguments.progressiveMode(),
                mappedArguments.contextSensitiveResolution(),
                mappedArguments.contextParameters(),
                mappedArguments.whenGuards(),
                mappedArguments.nestedTypeAliases(),
                mappedArguments.stringConcatMode(),
                mappedArguments.lambdaMode(),
                mappedArguments.samConversionMode(),
                mappedArguments.languageVersion(),
                mappedArguments.apiVersion(),
                mappedArguments.jvmDefaultMode(),
                mappedArguments.explicitApiMode(),
                mappedArguments.warningLevels(),
                mappedArguments.optIns(),
                null);
    }

    /** Maps Kotlin platform targeting onto the matching deterministic javac phase. */
    public static JavacOptions javacOptions(KotlinCompilerRunner.Options kotlinOptions) {
        KotlinCompilerRunner.Options options = Objects.requireNonNull(
                kotlinOptions,
                "Kotlin compilation options are required.");
        List<String> arguments = new ArrayList<>(2);
        if (options.javaParameters()) {
            arguments.add("-parameters");
        }
        if (options.suppressWarnings()) {
            arguments.add("-nowarn");
        }
        if (options.warningsAsErrors()) {
            arguments.add("-Werror");
        }
        return new JavacOptions(
                options.release(),
                StandardCharsets.UTF_8.name(),
                arguments,
                List.of(),
                options.hostPlatformApi(),
                options.useJdkRelease());
    }

    private static void requireUtf8(
            String encoding,
            KotlinCompilationScope scope) {
        if (encoding == null || encoding.isBlank()) {
            return;
        }
        try {
            if (Charset.forName(encoding).equals(StandardCharsets.UTF_8)) {
                return;
            }
        } catch (RuntimeException exception) {
            throw unsupported(
                    scope,
                    "[compiler].encoding is not a recognized UTF-8 encoding",
                    "Set [compiler].encoding to \"UTF-8\" for Kotlin sources.");
        }
        throw unsupported(
                scope,
                "[compiler].encoding is not UTF-8",
                "Set [compiler].encoding to \"UTF-8\"; the bounded Kotlin compiler reads authored"
                        + " sources as UTF-8.");
    }

    private static int featureVersion(
            String value,
            KotlinCompilationScope scope) {
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
                scope,
                "the effective Java release `" + value + "` is not a supported feature version",
                "Set [project].java to Java 8 or newer.");
    }

    private static String moduleName(
            ProjectConfig config,
            KotlinCompilationScope scope) {
        CompilerSettings compiler = config.compilerSettings();
        String configured = scope == KotlinCompilationScope.MAIN
                ? compiler.kotlinModule()
                : compiler.kotlinTestModule();
        if (!configured.isBlank()) {
            return configured;
        }
        String projectName = config.project().name();
        String value = projectName == null ? "" : projectName.strip().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            normalized.append(Character.isLetterOrDigit(character) || character == '_'
                    ? character
                    : '_');
        }
        if (normalized.isEmpty()) {
            normalized.append(scope.label());
        } else if (Character.isDigit(normalized.charAt(0))) {
            normalized.insert(0, "zolt_");
        }
        return normalized.append('_').append(scope.label()).toString();
    }

    private static KotlinCompileException unsupported(
            KotlinCompilationScope scope,
            String reason,
            String remediation) {
        return new KotlinCompileException(
                "Kotlin " + scope.label() + " compilation is not supported when " + reason + ". "
                        + remediation);
    }

}
