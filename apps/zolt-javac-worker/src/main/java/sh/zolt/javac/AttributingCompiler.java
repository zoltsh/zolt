package sh.zolt.javac;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Processor;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Runs a compile through the {@link JavaCompiler} task API with explicitly loaded, Filer-recording
 * processors so every generated output can be attributed to its originating type. Processors are
 * loaded from {@code -processorpath} (matching javac's own discovery) and passed via
 * {@code setProcessors}, which disables javac's discovery. The {@code -processorpath} option itself is
 * dropped from the task options because the processors are already instantiated.
 *
 * <p>Lombok-style processors that reflect into javac internals are given the raw environment (they emit
 * no Filer files, so they need no attribution); every other processor gets the recording environment.
 */
final class AttributingCompiler {
    private static final String LOMBOK_PACKAGE_PREFIX = "lombok.";

    private AttributingCompiler() {
    }

    static AttributionCompileResult compile(List<String> arguments) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StringWriter diagnostics = new StringWriter();
        PrintWriter diagnosticsWriter = new PrintWriter(diagnostics);
        AttributionCollector collector = new AttributionCollector();
        if (compiler == null) {
            return new AttributionCompileResult(
                    2, "error: the Zolt javac worker requires a JDK with the system Java compiler.\n", true, true, List.of());
        }
        try {
            List<Path> processorPath = pathOption(arguments, "-processorpath");
            List<String> sourceFiles = new ArrayList<>();
            List<String> options = partition(arguments, sourceFiles);
            StandardJavaFileManager standard = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
            try (OutputTrackingFileManager fileManager = new OutputTrackingFileManager(standard, collector);
                    ProcessorLoader.Loaded loaded = ProcessorLoader.load(processorPath)) {
                Iterable<? extends JavaFileObject> units = standard.getJavaFileObjectsFromStrings(sourceFiles);
                List<Processor> processors = wrap(loaded.processors(), collector);
                JavaCompiler.CompilationTask task =
                        compiler.getTask(diagnosticsWriter, fileManager, null, options, null, units);
                task.setProcessors(processors);
                boolean success = task.call();
                diagnosticsWriter.flush();
                return collector.result(success ? 0 : 1, diagnostics.toString());
            }
        } catch (RuntimeException | Error | java.io.IOException exception) {
            diagnosticsWriter.flush();
            return new AttributionCompileResult(
                    1,
                    diagnostics + "javac worker attribution failed: " + exception + System.lineSeparator(),
                    true,
                    true,
                    List.of());
        }
    }

    private static List<Processor> wrap(List<Processor> processors, AttributionCollector collector) {
        List<Processor> wrapped = new ArrayList<>();
        for (Processor processor : processors) {
            boolean recording = !processor.getClass().getName().startsWith(LOMBOK_PACKAGE_PREFIX);
            wrapped.add(new AttributionProcessor(processor, collector, recording));
        }
        return wrapped;
    }

    private static List<String> partition(List<String> arguments, List<String> sourceFiles) {
        List<String> options = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if ("-processorpath".equals(argument) && index + 1 < arguments.size()) {
                index++;
                continue;
            }
            if (argument.endsWith(".java")) {
                sourceFiles.add(argument);
            } else {
                options.add(argument);
            }
        }
        return options;
    }

    private static List<Path> pathOption(List<String> arguments, String option) {
        for (int index = 0; index + 1 < arguments.size(); index++) {
            if (option.equals(arguments.get(index))) {
                List<Path> paths = new ArrayList<>();
                for (String entry : arguments.get(index + 1).split(File.pathSeparator)) {
                    if (!entry.isBlank()) {
                        paths.add(Path.of(entry));
                    }
                }
                return paths;
            }
        }
        return List.of();
    }
}
