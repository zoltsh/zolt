package sh.zolt.build.compile.kotlin.kapt;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.classpath.Classpath;

/** Owned output and verified tooling inputs for one KAPT compiler invocation. */
public record KotlinKaptOptions(
        Path pluginJar,
        Classpath processorClasspath,
        Path generatedSourcesDirectory,
        Path generatedClassesDirectory,
        Path stubsDirectory) {
    public KotlinKaptOptions {
        pluginJar = absolute(pluginJar, "plugin JAR");
        processorClasspath = Objects.requireNonNull(
                processorClasspath,
                "KAPT processor classpath is required.");
        if (processorClasspath.entries().isEmpty()) {
            throw new KotlinCompileException(
                    "KAPT processor classpath must not be empty.");
        }
        if (processorClasspath.entries().stream().anyMatch(Objects::isNull)) {
            throw new KotlinCompileException(
                    "KAPT processor classpath entries are required.");
        }
        generatedSourcesDirectory = absolute(
                generatedSourcesDirectory,
                "generated-source output directory");
        generatedClassesDirectory = absolute(
                generatedClassesDirectory,
                "generated-class output directory");
        stubsDirectory = absolute(stubsDirectory, "stub output directory");
        List<Path> outputs = List.of(
                generatedSourcesDirectory,
                generatedClassesDirectory,
                stubsDirectory);
        if (overlaps(outputs)) {
            throw new KotlinCompileException(
                    "KAPT generated-source, generated-class, and stub output directories must not overlap.");
        }
    }

    private static Path absolute(Path path, String label) {
        if (path == null) {
            throw new KotlinCompileException("KAPT " + label + " is required.");
        }
        return path.toAbsolutePath().normalize();
    }

    private static boolean overlaps(List<Path> paths) {
        for (int first = 0; first < paths.size(); first++) {
            for (int second = first + 1; second < paths.size(); second++) {
                if (paths.get(first).startsWith(paths.get(second))
                        || paths.get(second).startsWith(paths.get(first))) {
                    return true;
                }
            }
        }
        return false;
    }
}
