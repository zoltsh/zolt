package sh.zolt.build.compile.kotlin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import sh.zolt.project.ProjectConfig;

/** Derives the JVM launch flag required by Kotlin preview-marked class files. */
public final class KotlinJvmPreviewPolicy {
    public static final String RUNTIME_ARGUMENT = "--enable-preview";

    private KotlinJvmPreviewPolicy() {
    }

    public static List<String> mainJvmArguments(ProjectConfig config) {
        return mainEnabled(config) ? List.of(RUNTIME_ARGUMENT) : List.of();
    }

    public static List<String> testJvmArguments(
            ProjectConfig config,
            List<String> configuredArguments) {
        Objects.requireNonNull(configuredArguments, "Configured test JVM arguments are required.");
        if (!testRuntimeEnabled(config) || configuredArguments.contains(RUNTIME_ARGUMENT)) {
            return List.copyOf(configuredArguments);
        }
        List<String> arguments = new ArrayList<>(configuredArguments);
        arguments.add(RUNTIME_ARGUMENT);
        return List.copyOf(arguments);
    }

    public static boolean mainEnabled(ProjectConfig config) {
        return Objects.requireNonNull(config, "Project configuration is required.")
                .compilerSettings()
                .mainKotlinJvmPreview();
    }

    public static boolean testRuntimeEnabled(ProjectConfig config) {
        ProjectConfig project = Objects.requireNonNull(config, "Project configuration is required.");
        return project.compilerSettings().testKotlinJvmPreview();
    }
}
