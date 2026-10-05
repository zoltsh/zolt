package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.classpath.Classpath;

/** Launches the isolated Kotlin/JVM compiler without exposing its tool closure to application code. */
public final class KotlinCompilerRunner {
    private final String pathSeparator;
    private final KotlinCompilerProcess compilerProcess;

    public KotlinCompilerRunner() {
        this(java.io.File.pathSeparator, KotlinCompilerProcess::runProcess);
    }

    KotlinCompilerRunner(String pathSeparator, ProcessRunner processRunner) {
        this.pathSeparator = pathSeparator;
        this.compilerProcess = new KotlinCompilerProcess(pathSeparator, processRunner);
    }

    public JavacResult compile(
            Path javaExecutable,
            Path jdkHome,
            List<Path> sources,
            Classpath compilerLauncherClasspath,
            Classpath compilationClasspath,
            Path outputDirectory,
            Options options) {
        return compile(
                javaExecutable,
                jdkHome,
                sources,
                compilerLauncherClasspath,
                compilationClasspath,
                outputDirectory,
                options,
                KotlinCompilationScope.MAIN);
    }

    public JavacResult compile(
            Path javaExecutable,
            Path jdkHome,
            List<Path> sources,
            Classpath compilerLauncherClasspath,
            Classpath compilationClasspath,
            Path outputDirectory,
            Options options,
            KotlinCompilationScope scope) {
        KotlinCompilationScope compilationScope = Objects.requireNonNull(
                scope,
                "Kotlin compilation scope is required.");
        if (options == null) {
            throw new KotlinCompileException(
                    "Kotlin " + compilationScope.label() + " compilation options are required.");
        }
        List<Path> sortedSources = sources == null
                ? List.of()
                : sources.stream().map(Path::normalize).sorted().toList();
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not create Kotlin " + compilationScope.label()
                            + " compilation output directory " + outputDirectory
                            + ". Check that the project directory is writable.",
                    exception);
        }
        if (sortedSources.isEmpty()) {
            return new JavacResult(0, outputDirectory, "");
        }

        List<String> compilerArguments = KotlinCompilerInvocationArguments.build(
                jdkHome,
                sortedSources,
                compilationClasspath,
                outputDirectory,
                options,
                pathSeparator);
        try (KotlinCompilerArgumentsFile argumentsFile =
                KotlinCompilerArgumentsFile.create(compilerArguments)) {
            ProcessResult result = compilerProcess.run(
                    javaExecutable,
                    compilerLauncherClasspath,
                    argumentsFile.commandArgument());
            if (result.exitCode() != 0) {
                throw new KotlinCompileException(
                        "Kotlin " + compilationScope.label() + " compilation failed with exit code "
                                + result.exitCode()
                                + ". Fix the Kotlin compilation errors and try again. Ensure "
                                + KotlinCompilerToolchain.COORDINATE
                                + " is selected in [toolchain.kotlin] and kotlin-stdlib is declared in"
                                + " " + compilationScope.runtimeDeclaration() + ".\n"
                                + result.output().stripTrailing());
            }
            return new JavacResult(sortedSources.size(), outputDirectory, result.output());
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not prepare or clean up the temporary Kotlin " + compilationScope.label()
                            + " compiler argument file. Check that the system temporary directory is writable "
                            + "and try again.",
                    exception);
        }
    }

    @FunctionalInterface
    interface ProcessRunner {
        ProcessResult run(List<String> command);
    }

    record ProcessResult(int exitCode, String output) {
    }

    public record Options(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease,
            boolean javaParameters,
            boolean warningsAsErrors,
            boolean suppressWarnings,
            boolean extraWarnings,
            boolean progressiveMode,
            boolean contextSensitiveResolution,
            boolean contextParameters,
            boolean whenGuards,
            boolean nestedTypeAliases,
            boolean annotationTargetAll,
            boolean jvmExposeBoxed,
            boolean consistentDataClassCopyVisibility,
            boolean emitJvmTypeAnnotations,
            String annotationDefaultTargetMode,
            String stringConcatMode,
            String lambdaMode,
            String samConversionMode,
            String languageVersion,
            String apiVersion,
            String jvmDefaultMode,
            String explicitApiMode,
            List<String> warningLevels,
            List<String> optIns,
            Path friendPath) {
        public Options(String release, String moduleName, boolean hostPlatformApi) {
            this(release, moduleName, hostPlatformApi, !hostPlatformApi, false, false, null);
        }

        public Options(
                String release,
                String moduleName,
                boolean hostPlatformApi,
                boolean useJdkRelease) {
            this(release, moduleName, hostPlatformApi, useJdkRelease, false, false, null);
        }

        public Options(
                String release,
                String moduleName,
                boolean hostPlatformApi,
                boolean useJdkRelease,
                boolean javaParameters) {
            this(release, moduleName, hostPlatformApi, useJdkRelease, javaParameters, false, null);
        }

        public Options(
                String release,
                String moduleName,
                boolean hostPlatformApi,
                boolean useJdkRelease,
                boolean javaParameters,
                boolean warningsAsErrors) {
            this(
                    release,
                    moduleName,
                    hostPlatformApi,
                    useJdkRelease,
                    javaParameters,
                    warningsAsErrors,
                    null);
        }

        public Options(
                String release,
                String moduleName,
                boolean hostPlatformApi,
                boolean useJdkRelease,
                Path friendPath) {
            this(release, moduleName, hostPlatformApi, useJdkRelease, false, false, friendPath);
        }

        /** Compatibility constructor for callers that predate mapped Kotlin warning policy. */
        public Options(
                String release,
                String moduleName,
                boolean hostPlatformApi,
                boolean useJdkRelease,
                boolean javaParameters,
                Path friendPath) {
            this(release, moduleName, hostPlatformApi, useJdkRelease, javaParameters, false, friendPath);
        }

        /** Compatibility constructor for callers that predate Kotlin language/API pinning. */
        public Options(
                String release,
                String moduleName,
                boolean hostPlatformApi,
                boolean useJdkRelease,
                boolean javaParameters,
                boolean warningsAsErrors,
                Path friendPath) {
            this(
                    release,
                    moduleName,
                    hostPlatformApi,
                    useJdkRelease,
                    javaParameters,
                    warningsAsErrors,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    friendPath);
        }

        public Options {
            release = require(release, "effective Java release");
            moduleName = require(moduleName, "module name");
            annotationDefaultTargetMode = optional(annotationDefaultTargetMode);
            stringConcatMode = optional(stringConcatMode);
            lambdaMode = optional(lambdaMode);
            samConversionMode = optional(samConversionMode);
            languageVersion = optional(languageVersion);
            apiVersion = optional(apiVersion);
            jvmDefaultMode = optional(jvmDefaultMode);
            explicitApiMode = optional(explicitApiMode);
            warningLevels = copyValues(warningLevels, "compiler warning level");
            optIns = copyOptIns(optIns);
            friendPath = friendPath == null ? null : friendPath.normalize();
            if (friendPath != null && friendPath.toString().contains(",")) {
                throw new KotlinCompileException(
                        "Kotlin test compilation cannot use a friend output path containing a comma because "
                                + "kotlinc treats commas as friend-path separators. Move the project to a path "
                                + "without commas and try again.");
            }
            if (hostPlatformApi && useJdkRelease) {
                throw new KotlinCompileException(
                        "Kotlin host platform-API mode cannot use -Xjdk-release.");
            }
        }

        public Options withFriendPath(Path path) {
            return new Options(
                    release,
                    moduleName,
                    hostPlatformApi,
                    useJdkRelease,
                    javaParameters,
                    warningsAsErrors,
                    suppressWarnings,
                    extraWarnings,
                    progressiveMode,
                    contextSensitiveResolution,
                    contextParameters,
                    whenGuards,
                    nestedTypeAliases,
                    annotationTargetAll,
                    jvmExposeBoxed,
                    consistentDataClassCopyVisibility,
                    emitJvmTypeAnnotations,
                    annotationDefaultTargetMode,
                    stringConcatMode,
                    lambdaMode,
                    samConversionMode,
                    languageVersion,
                    apiVersion,
                    jvmDefaultMode,
                    explicitApiMode,
                    warningLevels,
                    optIns,
                    Objects.requireNonNull(path, "Kotlin friend path is required."));
        }

        private static String require(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new KotlinCompileException("Kotlin compilation requires a " + label + ".");
            }
            return value.strip();
        }

        private static String optional(String value) {
            return value == null || value.isBlank() ? "" : value.strip();
        }

        private static List<String> copyOptIns(List<String> values) {
            return copyValues(values, "compiler opt-in annotation");
        }

        private static List<String> copyValues(List<String> values, String label) {
            if (values == null) {
                return List.of();
            }
            return values.stream()
                    .map(value -> require(value, label))
                    .toList();
        }
    }
}
