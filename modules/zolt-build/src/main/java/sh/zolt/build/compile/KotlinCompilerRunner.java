package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.kotlin.KotlinCompilerInvocationArguments;
import sh.zolt.build.compile.kotlin.kapt.KotlinKaptOptions;
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
            KotlinCompilerOptions options) {
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
            KotlinCompilerOptions options,
            KotlinCompilationScope scope) {
        return compile(
                javaExecutable,
                jdkHome,
                sources,
                compilerLauncherClasspath,
                compilationClasspath,
                outputDirectory,
                options,
                scope,
                null);
    }

    public JavacResult compile(
            Path javaExecutable,
            Path jdkHome,
            List<Path> sources,
            Classpath compilerLauncherClasspath,
            Classpath compilationClasspath,
            Path outputDirectory,
            KotlinCompilerOptions options,
            KotlinCompilationScope scope,
            KotlinKaptOptions kaptOptions) {
        return compile(
                javaExecutable,
                jdkHome,
                sources,
                compilerLauncherClasspath,
                compilationClasspath,
                outputDirectory,
                options,
                scope,
                kaptOptions,
                List.of());
    }

    public JavacResult compile(
            Path javaExecutable,
            Path jdkHome,
            List<Path> sources,
            Classpath compilerLauncherClasspath,
            Classpath compilationClasspath,
            Path outputDirectory,
            KotlinCompilerOptions options,
            KotlinCompilationScope scope,
            KotlinKaptOptions kaptOptions,
            List<Path> compilerPluginJars) {
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
                compilerPluginJars,
                kaptOptions,
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

}
