package sh.zolt.build.compile;

import sh.zolt.classpath.Classpath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.StringJoiner;

final class JavacCommandBuilder {
    private final String pathSeparator;

    JavacCommandBuilder(String pathSeparator) {
        this.pathSeparator = pathSeparator;
    }

    List<String> command(
            Path javac,
            List<Path> sources,
            Classpath classpath,
            Path outputDirectory,
            Classpath processorClasspath,
            Path generatedSourcesDirectory,
            JavacOptions options) {
        List<String> command = new ArrayList<>();
        command.add(javac.toString());
        command.add("-d");
        command.add(outputDirectory.toString());
        addPlatformOptions(command, options);
        if (!options.encoding().isBlank()) {
            command.add("-encoding");
            command.add(options.encoding());
        }
        List<Path> modulePathEntries = sortedModulePath(options);
        List<Path> classpathEntries = classpathWithoutModulePath(orderedEntries(classpath), modulePathEntries);
        addPath(command, "-classpath", classpathEntries);
        addPath(command, "--module-path", modulePathEntries);
        List<Path> processorClasspathEntries = orderedEntries(processorClasspath);
        if (processorClasspathEntries.isEmpty()) {
            command.add("-proc:none");
        } else {
            addPath(command, "-processorpath", combinedProcessorPath(processorClasspathEntries, classpathEntries));
        }
        if (generatedSourcesDirectory != null) {
            command.add("-s");
            command.add(generatedSourcesDirectory.toString());
        }
        command.addAll(options.arguments());
        sources.forEach(source -> command.add(source.toString()));
        return List.copyOf(command);
    }

    static List<Path> orderedEntries(Classpath classpath) {
        if (classpath == null) {
            return List.of();
        }
        return classpath.entries().stream()
                .map(Path::normalize)
                .toList();
    }

    private static void addPlatformOptions(List<String> command, JavacOptions options) {
        if (options.release().isBlank()) {
            return;
        }
        if (options.hostPlatformApi()) {
            // Host mode matches legacy Maven `-source/-target`; it is not reproducible across build JDKs.
            command.add("-source");
            command.add(options.release());
            command.add("-target");
            command.add(options.release());
        } else {
            // --release pins the platform API via ct.sym, making compilation reproducible across build JDKs.
            command.add("--release");
            command.add(options.release());
        }
    }

    private void addPath(List<String> command, String option, List<Path> entries) {
        if (entries.isEmpty()) {
            return;
        }
        StringJoiner joined = new StringJoiner(pathSeparator);
        entries.forEach(entry -> joined.add(entry.toString()));
        command.add(option);
        command.add(joined.toString());
    }

    private static List<Path> sortedModulePath(JavacOptions options) {
        return options.modulePath().stream()
                .map(Path::normalize)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private static List<Path> classpathWithoutModulePath(
            List<Path> classpathEntries,
            List<Path> modulePathEntries) {
        if (modulePathEntries.isEmpty()) {
            return classpathEntries;
        }
        LinkedHashSet<Path> moduleEntries = new LinkedHashSet<>(modulePathEntries);
        return classpathEntries.stream().filter(entry -> !moduleEntries.contains(entry)).toList();
    }

    private static List<Path> combinedProcessorPath(
            List<Path> processorEntries,
            List<Path> classpathEntries) {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        entries.addAll(processorEntries);
        entries.addAll(classpathEntries);
        return List.copyOf(entries);
    }
}
