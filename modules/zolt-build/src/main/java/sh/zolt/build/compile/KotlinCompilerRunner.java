package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.classpath.Classpath;

/** Launches the isolated Kotlin/JVM compiler without exposing its tool closure to application code. */
public final class KotlinCompilerRunner {
    private static final String COMPILER_MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler";

    private final String pathSeparator;
    private final ProcessRunner processRunner;

    public KotlinCompilerRunner() {
        this(java.io.File.pathSeparator, KotlinCompilerRunner::runProcess);
    }

    KotlinCompilerRunner(String pathSeparator, ProcessRunner processRunner) {
        this.pathSeparator = pathSeparator;
        this.processRunner = processRunner;
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

        ProcessResult result = processRunner.run(command(
                javaExecutable,
                jdkHome,
                sortedSources,
                compilerLauncherClasspath,
                compilationClasspath,
                outputDirectory,
                options));
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
    }

    private List<String> command(
            Path javaExecutable,
            Path jdkHome,
            List<Path> sources,
            Classpath compilerLauncherClasspath,
            Classpath compilationClasspath,
            Path outputDirectory,
            Options options) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-cp");
        command.add(joinedPath(entries(compilerLauncherClasspath)));
        command.add(COMPILER_MAIN);
        command.add("-no-stdlib");
        command.add("-no-reflect");
        command.add("-jdk-home");
        command.add(jdkHome.toString());
        if (!options.useJdkRelease()) {
            command.add("-jvm-target");
            command.add(jvmTarget(options.release()));
        } else {
            command.add("-Xjdk-release=" + options.release());
        }
        List<Path> compilationEntries = entries(compilationClasspath);
        if (!compilationEntries.isEmpty()) {
            command.add("-classpath");
            command.add(joinedPath(compilationEntries));
        }
        if (options.friendPath() != null) {
            command.add("-Xfriend-paths=" + options.friendPath());
        }
        command.add("-module-name");
        command.add(options.moduleName());
        command.add("-d");
        command.add(outputDirectory.toString());
        sources.forEach(source -> command.add(source.toString()));
        return List.copyOf(command);
    }

    private static String jvmTarget(String release) {
        return "8".equals(release) ? "1.8" : release;
    }

    private static List<Path> entries(Classpath classpath) {
        return classpath == null
                ? List.of()
                : classpath.entries().stream().map(Path::normalize).toList();
    }

    private String joinedPath(List<Path> entries) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        entries.forEach(entry -> joiner.add(entry.toString()));
        return joiner.toString();
    }

    private static ProcessResult runProcess(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (BuildCancellation.Registration ignored = ProcessCancellation.register(process)) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return new ProcessResult(process.waitFor(), output);
            }
        } catch (IOException exception) {
            throw new KotlinCompileException(
                    "Could not run the Kotlin compiler. Check that the configured JDK is installed and readable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KotlinCompileException(
                    "Kotlin compilation was interrupted. Try the command again.", exception);
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
            Path friendPath) {
        public Options(String release, String moduleName, boolean hostPlatformApi) {
            this(release, moduleName, hostPlatformApi, !hostPlatformApi, null);
        }

        public Options(
                String release,
                String moduleName,
                boolean hostPlatformApi,
                boolean useJdkRelease) {
            this(release, moduleName, hostPlatformApi, useJdkRelease, null);
        }

        public Options {
            release = require(release, "effective Java release");
            moduleName = require(moduleName, "module name");
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
                    Objects.requireNonNull(path, "Kotlin friend path is required."));
        }

        private static String require(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new KotlinCompileException("Kotlin compilation requires a " + label + ".");
            }
            return value.strip();
        }
    }
}
