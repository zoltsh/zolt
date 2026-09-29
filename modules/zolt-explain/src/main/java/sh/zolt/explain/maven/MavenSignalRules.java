package sh.zolt.explain.maven;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure classification and message helpers used by {@link MavenStaticProjectInspector} when deriving
 * signals from an inspection. Kept apart so the inspector stays focused on orchestrating the audit.
 */
final class MavenSignalRules {
    private MavenSignalRules() {
    }

    static String reactorMessage(int members, List<String> profileModules) {
        if (profileModules.isEmpty()) {
            return "Multi-module reactor with " + members + " module(s); `zolt explain --emit-toml`"
                    + " emits a Zolt workspace with a root [workspace] plus one member draft per module.";
        }
        return "Multi-module reactor with " + members + " top-level module(s) plus "
                + profileModules.size()
                + " profile-declared module(s) omitted from default workspace coverage: "
                + String.join(", ", profileModules)
                + "; `zolt explain --emit-toml` emits a Zolt workspace with a root [workspace]"
                + " plus one member draft per top-level module.";
    }

    static boolean knownPlugin(String coordinate) {
        return coordinate.contains(":maven-compiler-plugin")
                || coordinate.contains(":maven-surefire-plugin")
                || coordinate.contains(":maven-failsafe-plugin")
                || coordinate.contains(":spring-boot-maven-plugin");
    }

    static boolean gmavenPlusPlugin(String coordinate) {
        return plugin(coordinate, "org.codehaus.gmavenplus", "gmavenplus-plugin");
    }

    static boolean kotlinMavenPlugin(String coordinate) {
        return plugin(coordinate, "org.jetbrains.kotlin", "kotlin-maven-plugin");
    }

    static boolean boundedKotlinJvmCompilation(MavenPluginInspection plugin) {
        if (!kotlinMavenPlugin(plugin.coordinate())) {
            return false;
        }
        return plugin.goals().stream()
                .map(goal -> goal.toLowerCase(Locale.ROOT))
                .allMatch(Set.of("compile", "test-compile")::contains);
    }

    /**
     * The conventional gmavenplus compile pair is fully replaced by Zolt's main/test compilation.
     * Any extra goal or lifecycle phase stays visible to the caller and must be reviewed as arbitrary
     * Maven behavior rather than being silently treated as compilation.
     */
    static boolean replacedGroovyCompilation(MavenPluginInspection plugin) {
        if (!gmavenPlusPlugin(plugin.coordinate()) || plugin.goals().isEmpty()) {
            return false;
        }
        Set<String> goals = plugin.goals().stream()
                .map(goal -> goal.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!Set.of("compile", "compiletests").containsAll(goals)) {
            return false;
        }
        Set<String> expectedPhases = new LinkedHashSet<>();
        if (goals.contains("compile")) {
            expectedPhases.add("compile");
        }
        if (goals.contains("compiletests")) {
            expectedPhases.add("test-compile");
        }
        Set<String> actualPhases = plugin.phases().stream()
                .map(phase -> phase.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return actualPhases.equals(expectedPhases);
    }

    static String phaseSuffix(MavenPluginInspection plugin) {
        if (plugin.phases().isEmpty()) {
            return "";
        }
        return " in effective lifecycle phase(s) " + plugin.phases();
    }

    static boolean unsupportedLanguagePlugin(String coordinate) {
        String lower = coordinate.toLowerCase();
        return lower.contains(":scala-maven-plugin")
                || lower.contains(":android-maven-plugin");
    }

    private static boolean plugin(String coordinate, String groupId, String artifactId) {
        String[] parts = coordinate.toLowerCase(Locale.ROOT).split(":", -1);
        return parts.length >= 2
                && groupId.equals(parts[0])
                && artifactId.equals(parts[1]);
    }

    static boolean unsupportedFrameworkNativePlugin(MavenPluginInspection plugin) {
        String lower = plugin.coordinate().toLowerCase();
        if (lower.contains(":native-maven-plugin") || lower.contains(":micronaut-maven-plugin")) {
            return true;
        }
        if (!lower.contains(":spring-boot-maven-plugin")) {
            return false;
        }
        return plugin.goals().stream()
                .map(String::toLowerCase)
                .anyMatch(goal -> goal.contains("aot") || goal.contains("build-image") || goal.contains("native"));
    }

    static List<MavenDependencyInspection> concat(
            List<MavenDependencyInspection> first,
            List<MavenDependencyInspection> second) {
        List<MavenDependencyInspection> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }
}
