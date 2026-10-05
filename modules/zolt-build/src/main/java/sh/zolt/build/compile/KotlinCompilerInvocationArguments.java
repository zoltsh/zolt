package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import sh.zolt.classpath.Classpath;

/** Builds the deterministic Kotlin compiler argument-file payload. */
final class KotlinCompilerInvocationArguments {
    private KotlinCompilerInvocationArguments() {
    }

    static List<String> build(
            Path jdkHome,
            List<Path> sources,
            Classpath compilationClasspath,
            Path outputDirectory,
            KotlinCompilerRunner.Options options,
            String pathSeparator) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-no-stdlib");
        arguments.add("-no-reflect");
        arguments.add("-jdk-home");
        arguments.add(jdkHome.toString());
        if (!options.useJdkRelease()) {
            arguments.add("-jvm-target");
            arguments.add("8".equals(options.release()) ? "1.8" : options.release());
        } else {
            arguments.add("-Xjdk-release=" + options.release());
        }
        if (options.javaParameters()) {
            arguments.add("-java-parameters");
        }
        if (options.warningsAsErrors()) {
            arguments.add("-Werror");
        }
        addVersion(arguments, "-language-version", options.languageVersion());
        addVersion(arguments, "-api-version", options.apiVersion());
        if (!options.jvmDefaultMode().isEmpty()) {
            arguments.add("-jvm-default=" + options.jvmDefaultMode());
        }
        options.optIns().forEach(optIn -> arguments.add("-opt-in=" + optIn));
        List<Path> compilationEntries = entries(compilationClasspath);
        if (!compilationEntries.isEmpty()) {
            arguments.add("-classpath");
            arguments.add(joinedPath(compilationEntries, pathSeparator));
        }
        if (options.friendPath() != null) {
            arguments.add("-Xfriend-paths=" + options.friendPath());
        }
        arguments.add("-module-name");
        arguments.add(options.moduleName());
        arguments.add("-d");
        arguments.add(outputDirectory.toString());
        sources.forEach(source -> arguments.add(source.toString()));
        return List.copyOf(arguments);
    }

    private static void addVersion(
            List<String> arguments,
            String name,
            String value) {
        if (!value.isEmpty()) {
            arguments.add(name);
            arguments.add(value);
        }
    }

    private static List<Path> entries(Classpath classpath) {
        return classpath == null
                ? List.of()
                : classpath.entries().stream().map(Path::normalize).toList();
    }

    private static String joinedPath(
            List<Path> entries,
            String pathSeparator) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        entries.forEach(entry -> joiner.add(entry.toString()));
        return joiner.toString();
    }
}
