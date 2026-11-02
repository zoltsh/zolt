package sh.zolt.build.generatedsource.ksp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Builds the standalone KSP2 JVM command without consulting ambient process state. */
final class KspJvmCommandBuilder {
    static final String MAIN_CLASS = "com.google.devtools.ksp.cmdline.KSPJvmMain";

    private final String pathSeparator;

    KspJvmCommandBuilder(String pathSeparator) {
        if (pathSeparator == null || pathSeparator.isEmpty()) {
            throw new IllegalArgumentException("Path separator must not be empty.");
        }
        this.pathSeparator = pathSeparator;
    }

    List<String> command(KspJvmInvocation invocation) {
        List<String> command = new ArrayList<>();
        command.add(invocation.javaExecutable().toString());
        command.add("-Dksp.logging=warn");
        command.add("-cp");
        command.add(joinPaths(invocation.engineClasspath()));
        command.add(MAIN_CLASS);
        option(command, "jvm-target", invocation.jvmTarget());
        option(command, "module-name", invocation.moduleName());
        option(command, "source-roots", joinPaths(invocation.kotlinSourceRoots()));
        optionalPaths(command, "java-source-roots", invocation.javaSourceRoots());
        optionalPaths(command, "libraries", invocation.libraries());
        optionalPaths(command, "friends", invocation.friends());
        option(command, "jdk-home", invocation.jdkHome().toString());
        option(command, "project-base-dir", invocation.projectBaseDirectory().toString());
        option(command, "output-base-dir", invocation.outputBaseDirectory().toString());
        option(command, "caches-dir", invocation.cachesDirectory().toString());
        option(command, "class-output-dir", invocation.classOutputDirectory().toString());
        option(command, "kotlin-output-dir", invocation.kotlinOutputDirectory().toString());
        option(command, "java-output-dir", invocation.javaOutputDirectory().toString());
        option(command, "resource-output-dir", invocation.resourceOutputDirectory().toString());
        option(command, "language-version", invocation.languageVersion());
        option(command, "api-version", invocation.apiVersion());
        optional(command, "jvm-default-mode", invocation.jvmDefaultMode());
        option(command, "incremental", "false");
        if (invocation.warningsAsErrors()) {
            option(command, "all-warnings-as-errors", "true");
        }
        if (invocation.mapAnnotationArgumentsInJava()) {
            option(command, "map-annotation-arguments-in-java", "true");
        }
        if (!invocation.processorOptions().isEmpty()) {
            option(command, "processor-options", joinOptions(invocation.processorOptions()));
        }
        command.add(joinPaths(invocation.processorClasspath()));
        return List.copyOf(command);
    }

    private static void option(List<String> command, String name, String value) {
        command.add("-" + name + "=" + value);
    }

    private static void optional(List<String> command, String name, String value) {
        if (!value.isEmpty()) {
            option(command, name, value);
        }
    }

    private void optionalPaths(List<String> command, String name, List<Path> paths) {
        if (!paths.isEmpty()) {
            option(command, name, joinPaths(paths));
        }
    }

    private String joinPaths(List<Path> paths) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        paths.forEach(path -> joiner.add(path.toString()));
        return joiner.toString();
    }

    private String joinOptions(Map<String, String> options) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        options.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    rejectOptionDelimiter(entry.getKey(), "name");
                    rejectOptionDelimiter(entry.getValue(), "value");
                    if (entry.getKey().contains("=")) {
                        throw new IllegalArgumentException(
                                "KSP processor option names must not contain `=`.");
                    }
                    joiner.add(entry.getKey() + "=" + entry.getValue());
                });
        return joiner.toString();
    }

    private void rejectOptionDelimiter(String value, String label) {
        if (value.contains(pathSeparator)) {
            throw new IllegalArgumentException(
                    "KSP processor option " + label + " must not contain the platform path separator.");
        }
    }
}
