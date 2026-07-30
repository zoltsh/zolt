package sh.zolt.build.compile;

import sh.zolt.build.GroovyCompileException;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import sh.zolt.classpath.Classpath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

public final class GroovyCompilerRunner {
    private static final String GROOVY_COMPILER_MAIN = "org.codehaus.groovy.tools.FileSystemCompiler";

    private final String pathSeparator;
    private final ProcessRunner processRunner;

    public GroovyCompilerRunner() {
        this(java.io.File.pathSeparator, GroovyCompilerRunner::runProcess);
    }

    public GroovyCompilerRunner(String pathSeparator, ProcessRunner processRunner) {
        this.pathSeparator = pathSeparator;
        this.processRunner = processRunner;
    }

    public JavacResult compile(
            Path javaExecutable,
            List<Path> sources,
            Classpath classpath,
            Path outputDirectory) {
        List<Path> sortedSources = sources.stream().map(Path::normalize).sorted().toList();
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException exception) {
            throw new GroovyCompileException(
                    "Could not create Groovy test compilation output directory "
                            + outputDirectory
                            + ". Check that the project directory is writable.",
                    exception);
        }
        if (sortedSources.isEmpty()) {
            return new JavacResult(0, outputDirectory, "");
        }

        ProcessResult result = processRunner.run(command(javaExecutable, sortedSources, classpath, outputDirectory));
        if (result.exitCode() != 0) {
            throw new GroovyCompileException(
                    "Groovy test compilation failed with exit code "
                            + result.exitCode()
                            + ". Fix the Groovy compilation errors and try again. "
                            + "Ensure Groovy compiler tooling such as org.apache.groovy:groovy is declared in [test.dependencies].\n"
                            + result.output().stripTrailing());
        }
        return new JavacResult(sortedSources.size(), outputDirectory, result.output());
    }

    private List<String> command(
            Path javaExecutable,
            List<Path> sources,
            Classpath classpath,
            Path outputDirectory) {
        List<Path> classpathEntries = sortedEntries(classpath);
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-cp");
        command.add(joinedPath(classpathEntries));
        command.add(GROOVY_COMPILER_MAIN);
        command.add("-d");
        command.add(outputDirectory.toString());
        if (!classpathEntries.isEmpty()) {
            command.add("-classpath");
            command.add(joinedPath(classpathEntries));
        }
        for (Path source : sources) {
            command.add(source.toString());
        }
        return List.copyOf(command);
    }

    private static List<Path> sortedEntries(Classpath classpath) {
        if (classpath == null) {
            return List.of();
        }
        return classpath.entries().stream()
                .map(Path::normalize)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private String joinedPath(List<Path> entries) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        for (Path entry : entries) {
            joiner.add(entry.toString());
        }
        return joiner.toString();
    }

    private static ProcessResult runProcess(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (BuildCancellation.Registration ignored =
                    ProcessCancellation.register(process)) {
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();
                return new ProcessResult(exitCode, output);
            }
        } catch (IOException exception) {
            throw new GroovyCompileException(
                    "Could not run the Groovy compiler. Check that the configured JDK is installed and readable.",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GroovyCompileException("Groovy test compilation was interrupted. Try the build again.", exception);
        }
    }

    @FunctionalInterface
    public interface ProcessRunner {
        ProcessResult run(List<String> command);
    }

    public record ProcessResult(int exitCode, String output) {
    }
}
