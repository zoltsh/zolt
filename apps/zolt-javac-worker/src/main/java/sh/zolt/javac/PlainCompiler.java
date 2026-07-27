package sh.zolt.javac;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/**
 * Compiles ordinary requests while retaining javac's standard file manager on each worker thread.
 *
 * <p>{@link StandardJavaFileManager} explicitly supports reuse across sequential compilation tasks
 * and keeps JAR indexes warm. A thread-local instance preserves that benefit without sharing the
 * non-thread-safe manager between concurrent workspace compilation requests.
 */
final class PlainCompiler {
    private final JavaCompiler compiler;
    private final ThreadLocal<ReusableFileManager> fileManagers;

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
        this.fileManagers = ThreadLocal.withInitial(
                () -> ReusableFileManager.create(fileManagerFactory.get()));
    }

    Result compile(List<String> arguments) {
        if (compiler == null) {
            return new Result(
                    2,
                    "error: the Zolt javac worker requires a JDK with the system Java compiler.\n");
        }
        StringWriter diagnostics = new StringWriter();
        PrintWriter diagnosticsWriter = new PrintWriter(diagnostics);
        ReusableFileManager reusable = null;
        try {
            List<String> sourceFiles = new ArrayList<>();
            List<String> options = partition(arguments, sourceFiles);
            reusable = fileManagers.get();
            reusable.resetInputLocations();
            StandardJavaFileManager fileManager = reusable.fileManager();
            Iterable<? extends JavaFileObject> units =
                    fileManager.getJavaFileObjectsFromStrings(sourceFiles);
            boolean success = compiler
                    .getTask(diagnosticsWriter, fileManager, null, options, null, units)
                    .call();
            diagnosticsWriter.flush();
            return new Result(success ? 0 : 1, diagnostics.toString());
        } catch (RuntimeException | Error | IOException exception) {
            discard(reusable);
            diagnosticsWriter.flush();
            return new Result(
                    1,
                    diagnostics + "javac worker failed: " + exception + System.lineSeparator());
        }
    }

    private void discard(ReusableFileManager reusable) {
        fileManagers.remove();
        if (reusable != null) {
            try {
                reusable.fileManager().close();
            } catch (IOException ignored) {
                // The failed manager is already detached from the worker thread.
            }
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

    private record ReusableFileManager(
            StandardJavaFileManager fileManager,
            List<Path> defaultClassPath) {
        private static ReusableFileManager create(StandardJavaFileManager fileManager) {
            Iterable<? extends Path> paths =
                    fileManager.getLocationAsPaths(StandardLocation.CLASS_PATH);
            List<Path> defaultClassPath = paths == null
                    ? List.of()
                    : StreamSupport.stream(paths.spliterator(), false)
                            .map(path -> (Path) path)
                            .toList();
            return new ReusableFileManager(fileManager, defaultClassPath);
        }

        private void resetInputLocations() throws IOException {
            fileManager.setLocationFromPaths(
                    StandardLocation.CLASS_PATH,
                    defaultClassPath);
            fileManager.setLocationFromPaths(
                    StandardLocation.MODULE_PATH,
                    List.of());
        }
    }
}
