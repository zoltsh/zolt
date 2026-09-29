package sh.zolt.explain.emit;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import sh.zolt.explain.maven.MavenPluginInspection;
import sh.zolt.explain.maven.MavenProjectInspection;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;

/** Fail-closed checks for Maven behavior that a Kotlin draft cannot preserve. */
final class MavenKotlinDraftSafety {
    private MavenKotlinDraftSafety() {
    }

    static boolean hasOtherLifecycleExtension(MavenProjectInspection project) {
        return project.plugins().stream()
                .filter(plugin -> !plugin.pluginManagement())
                .filter(plugin -> !kotlinPlugin(plugin.coordinate()))
                .map(MavenPluginInspection::extensions)
                .anyMatch(value -> !value.isBlank() && !"false".equalsIgnoreCase(value));
    }

    static boolean hasSupportedCompilerPlugin(MavenProjectInspection project) {
        List<MavenPluginInspection> plugins = project.plugins().stream()
                .filter(plugin -> coordinate(
                        plugin.coordinate(), "org.apache.maven.plugins", "maven-compiler-plugin"))
                .toList();
        if (plugins.size() != 1) {
            return false;
        }
        MavenPluginInspection plugin = plugins.getFirst();
        return supportedCompilerVersion(plugin.coordinate())
                && !plugin.configurationPresent()
                && plugin.goals().isEmpty()
                && plugin.phases().isEmpty()
                && plugin.disabledExecutions().isEmpty()
                && plugin.execInvocations().isEmpty()
                && !plugin.databaseBackedCodegen()
                && !plugin.activeConfiguredExecutionsPresent()
                && !plugin.pluginDependenciesPresent()
                && (plugin.extensions().isBlank()
                        || "false".equalsIgnoreCase(plugin.extensions()));
    }

    static boolean hasUtf8SourceEncoding(MavenProjectInspection project) {
        String value = project.sourceEncoding();
        if (value.isBlank() || value.contains("${")) {
            return false;
        }
        try {
            return Charset.forName(value).equals(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static boolean hasSafeKotlinModuleName(MavenProjectInspection project) {
        try {
            ProjectPaths.filenameComponent("Maven artifactId", project.artifactId());
            return true;
        } catch (ProjectPathException exception) {
            return false;
        }
    }

    private static boolean supportedCompilerVersion(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        if (parts.length != 3 || !parts[2].matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)*")) {
            return false;
        }
        String[] version = parts[2].split("\\.");
        try {
            return Integer.parseInt(version[0]) == 3
                    && Integer.parseInt(version[1]) >= 13;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    static boolean hasGenerationBehavior(MavenProjectInspection project) {
        return project.plugins().stream()
                .filter(plugin -> !plugin.pluginManagement())
                .anyMatch(plugin -> !plugin.execInvocations().isEmpty()
                        || plugin.databaseBackedCodegen()
                        || plugin.phases().stream().anyMatch(MavenKotlinDraftSafety::generationPhase)
                        || plugin.goals().stream().anyMatch(MavenKotlinDraftSafety::generationGoal)
                        || (plugin.activeConfiguredExecutionsPresent()
                                && !ordinaryTestExecution(plugin)));
    }

    private static boolean generationPhase(String phase) {
        String normalized = phase.toLowerCase(Locale.ROOT);
        return normalized.startsWith("generate-")
                || normalized.equals("process-sources")
                || normalized.equals("process-test-sources")
                || normalized.equals("process-resources")
                || normalized.equals("process-test-resources");
    }

    private static boolean generationGoal(String goal) {
        String normalized = goal.toLowerCase(Locale.ROOT);
        return normalized.equals("generate")
                || normalized.equals("generate-sources")
                || normalized.equals("generate-test-sources")
                || normalized.equals("antlr4")
                || normalized.equals("javacc")
                || normalized.equals("xjc")
                || normalized.equals("wsimport")
                || normalized.contains("protobuf");
    }

    private static boolean ordinaryTestExecution(MavenPluginInspection plugin) {
        if (coordinate(plugin.coordinate(), "org.apache.maven.plugins", "maven-surefire-plugin")) {
            return plugin.phases().stream().allMatch("test"::equals)
                    && plugin.goals().stream().allMatch("test"::equals);
        }
        if (coordinate(plugin.coordinate(), "org.apache.maven.plugins", "maven-failsafe-plugin")) {
            return plugin.phases().stream().allMatch(
                            phase -> phase.equals("integration-test") || phase.equals("verify"))
                    && plugin.goals().stream().allMatch(
                            goal -> goal.equals("integration-test") || goal.equals("verify"));
        }
        return false;
    }

    private static boolean kotlinPlugin(String coordinate) {
        return coordinate(coordinate, "org.jetbrains.kotlin", "kotlin-maven-plugin");
    }

    private static boolean coordinate(String coordinate, String group, String artifact) {
        String[] parts = coordinate.split(":", -1);
        return parts.length >= 2 && group.equals(parts[0]) && artifact.equals(parts[1]);
    }
}
