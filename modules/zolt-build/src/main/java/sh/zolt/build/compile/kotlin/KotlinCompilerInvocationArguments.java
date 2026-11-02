package sh.zolt.build.compile.kotlin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.build.compile.kotlin.kapt.KotlinAnnotationProcessorOptions;
import sh.zolt.build.compile.kotlin.kapt.KotlinKaptOptions;
import sh.zolt.classpath.Classpath;

/** Builds the deterministic Kotlin compiler argument-file payload. */
public final class KotlinCompilerInvocationArguments {
    private KotlinCompilerInvocationArguments() {
    }

    public static List<String> build(
            Path jdkHome,
            List<Path> sources,
            Classpath compilationClasspath,
            Path outputDirectory,
            KotlinCompilerOptions options,
            String pathSeparator) {
        return build(
                jdkHome,
                sources,
                compilationClasspath,
                outputDirectory,
                options,
                List.of(),
                List.of(),
                null,
                pathSeparator);
    }

    public static List<String> build(
            Path jdkHome,
            List<Path> sources,
            Classpath compilationClasspath,
            Path outputDirectory,
            KotlinCompilerOptions options,
            KotlinKaptOptions kaptOptions,
            String pathSeparator) {
        return build(
                jdkHome,
                sources,
                compilationClasspath,
                outputDirectory,
                options,
                List.of(),
                List.of(),
                kaptOptions,
                pathSeparator);
    }

    public static List<String> build(
            Path jdkHome,
            List<Path> sources,
            Classpath compilationClasspath,
            Path outputDirectory,
            KotlinCompilerOptions options,
            List<Path> compilerPluginJars,
            List<KotlinCompilerPluginOption> compilerPluginOptions,
            KotlinKaptOptions kaptOptions,
            String pathSeparator) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-no-stdlib");
        arguments.add("-no-reflect");
        arguments.add("-jdk-home");
        arguments.add(jdkHome.toString());
        addTarget(arguments, options);
        KotlinCompilerPolicyArguments.addTo(arguments, options.policy());
        addCompilerPlugins(arguments, compilerPluginJars, compilerPluginOptions);
        addKaptArguments(
                arguments,
                kaptOptions,
                options.policy().annotationProcessing().processorOptions());
        addClasspath(arguments, compilationClasspath, pathSeparator);
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

    private static void addTarget(
            List<String> arguments,
            KotlinCompilerOptions options) {
        if (!options.useJdkRelease()) {
            arguments.add("-jvm-target");
            arguments.add("8".equals(options.release()) ? "1.8" : options.release());
            return;
        }
        arguments.add("-Xjdk-release=" + options.release());
    }

    private static void addCompilerPlugins(
            List<String> arguments,
            List<Path> pluginJars,
            List<KotlinCompilerPluginOption> pluginOptions) {
        if (pluginJars == null) {
            return;
        }
        pluginJars.forEach(plugin ->
                arguments.add("-Xplugin=" + plugin.toAbsolutePath().normalize()));
        if (pluginOptions != null) {
            pluginOptions.forEach(option -> {
                arguments.add("-P");
                arguments.add(option.argument());
            });
        }
    }

    private static void addKaptArguments(
            List<String> arguments,
            KotlinKaptOptions options,
            Map<String, String> annotationProcessorOptions) {
        if (options == null) {
            return;
        }
        arguments.add("-Xplugin=" + options.pluginJar());
        addPluginOption(arguments, "aptMode", "stubsAndApt");
        addPluginOption(arguments, "sources", options.generatedSourcesDirectory().toString());
        addPluginOption(arguments, "classes", options.generatedClassesDirectory().toString());
        addPluginOption(arguments, "stubs", options.stubsDirectory().toString());
        options.processorClasspath().entries().forEach(path ->
                addPluginOption(
                        arguments,
                        "apclasspath",
                        path.toAbsolutePath().normalize().toString()));
        KotlinAnnotationProcessorOptions processorOptions =
                new KotlinAnnotationProcessorOptions(annotationProcessorOptions);
        if (!processorOptions.isEmpty()) {
            addPluginOption(arguments, "apoptions", processorOptions.encoded());
        }
        addPluginOption(arguments, "includeCompileClasspath", "false");
        addPluginOption(arguments, "correctErrorTypes", "true");
        addPluginOption(arguments, "mapDiagnosticLocations", "true");
    }

    private static void addPluginOption(
            List<String> arguments,
            String name,
            String value) {
        arguments.add("-P");
        arguments.add("plugin:org.jetbrains.kotlin.kapt3:" + name + "=" + value);
    }

    private static void addClasspath(
            List<String> arguments,
            Classpath classpath,
            String pathSeparator) {
        List<Path> entries = entries(classpath);
        if (entries.isEmpty()) {
            return;
        }
        arguments.add("-classpath");
        StringJoiner joiner = new StringJoiner(pathSeparator);
        entries.forEach(entry -> joiner.add(entry.toString()));
        arguments.add(joiner.toString());
    }

    private static List<Path> entries(Classpath classpath) {
        return classpath == null
                ? List.of()
                : classpath.entries().stream().map(Path::normalize).toList();
    }
}
