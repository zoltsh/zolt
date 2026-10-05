package sh.zolt.build.compile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.classpath.Classpath;

/** Builds and runs the isolated Kotlin compiler launcher process. */
final class KotlinCompilerProcess {
    private static final String COMPILER_MAIN = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler";

    private final String pathSeparator;
    private final KotlinCompilerRunner.ProcessRunner processRunner;

    KotlinCompilerProcess(
            String pathSeparator,
            KotlinCompilerRunner.ProcessRunner processRunner) {
        this.pathSeparator = pathSeparator;
        this.processRunner = processRunner;
    }

    KotlinCompilerRunner.ProcessResult run(
            Path javaExecutable,
            Classpath compilerLauncherClasspath,
            String argumentsFile) {
        return processRunner.run(List.of(
                javaExecutable.toString(),
                "-cp",
                joinedPath(entries(compilerLauncherClasspath)),
                COMPILER_MAIN,
                argumentsFile));
    }

    static KotlinCompilerRunner.ProcessResult runProcess(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (BuildCancellation.Registration ignored = ProcessCancellation.register(process)) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return new KotlinCompilerRunner.ProcessResult(process.waitFor(), output);
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
}
