package sh.zolt.javac;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Keeps the compiler and worker JVM warm while opening a fresh file manager for each request.
 * Javac retains canonical source paths and JAR handles in a reused manager, even after flush;
 * a fresh manager observes retargeted symlinks and replaced dependencies on every compilation.
 */
final class PlainCompiler {
    private final JavaCompiler compiler;
    private final Supplier<StandardJavaFileManager> fileManagerFactory;

    PlainCompiler() {
        this(ToolProvider.getSystemJavaCompiler());
    }

    PlainCompiler(JavaCompiler compiler) {
        this(
                compiler,
                () -> compiler.getStandardFileManager(
                        null,
                        null,
                        StandardCharsets.UTF_8));
    }

    PlainCompiler(
            JavaCompiler compiler,
            Supplier<StandardJavaFileManager> fileManagerFactory) {
        this.compiler = compiler;
        this.fileManagerFactory = fileManagerFactory;
    }

    Result compile(List<String> arguments) {
        if (compiler == null) {
            return new Result(
                    2,
                    "error: the Zolt javac worker requires a JDK with the system Java compiler.\n");
        }
        StringWriter diagnostics = new StringWriter();
        PrintWriter diagnosticsWriter = new PrintWriter(diagnostics);
        try (StandardJavaFileManager fileManager = fileManagerFactory.get()) {
            List<String> sourceFiles = new ArrayList<>();
            List<String> options = partition(arguments, sourceFiles);
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromStrings(sourceFiles);
            boolean success = compiler
                    .getTask(diagnosticsWriter, fileManager, null, options, null, units)
                    .call();
            diagnosticsWriter.flush();
            return new Result(success ? 0 : 1, diagnostics.toString());
        } catch (RuntimeException | Error | IOException exception) {
            diagnosticsWriter.flush();
            return new Result(
                    1,
                    diagnostics + "javac worker failed: " + exception + System.lineSeparator());
        }
    }

    private static List<String> partition(
            List<String> arguments,
            List<String> sourceFiles) {
        List<String> options = new ArrayList<>();
        for (String argument : arguments) {
            if (argument.endsWith(".java")) {
                sourceFiles.add(argument);
            } else {
                options.add(argument);
            }
        }
        return options;
    }

    record Result(int exitCode, String diagnostics) {
    }

}
