package sh.zolt.explain.maven;

import static sh.zolt.explain.maven.MavenXml.child;
import static sh.zolt.explain.maven.MavenXml.children;
import static sh.zolt.explain.maven.MavenXml.text;
import static sh.zolt.explain.maven.MavenXml.texts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Element;

final class MavenPluginParser {
    private MavenPluginParser() {
    }

    static List<MavenPluginInspection> parse(Element project, MavenPomProperties properties) {
        Optional<Element> build = child(project, "build");
        if (build.isEmpty()) {
            return List.of();
        }
        List<MavenPluginInspection> plugins = new ArrayList<>();
        plugins.addAll(pluginList(child(build.orElseThrow(), "plugins"), false, properties));
        child(build.orElseThrow(), "pluginManagement")
                .flatMap(element -> child(element, "plugins"))
                .ifPresent(element -> plugins.addAll(pluginList(Optional.of(element), true, properties)));
        plugins.sort(Comparator
                .comparing(MavenPluginInspection::coordinate)
                .thenComparing(MavenPluginInspection::pluginManagement));
        return plugins;
    }

    private static List<MavenPluginInspection> pluginList(
            Optional<Element> pluginsElement,
            boolean pluginManagement,
            MavenPomProperties properties) {
        if (pluginsElement.isEmpty()) {
            return List.of();
        }
        List<MavenPluginInspection> plugins = new ArrayList<>();
        for (Element plugin : children(pluginsElement.orElseThrow(), "plugin")) {
            String groupId = properties.interpolate(text(plugin, "groupId").orElse("org.apache.maven.plugins"));
            String artifactId = properties.interpolate(text(plugin, "artifactId").orElse("unknown-plugin"));
            String version = properties.interpolate(text(plugin, "version").orElse(""));
            String coordinate = groupId + ":" + artifactId + (version.isBlank() ? "" : ":" + version);
            List<Element> executions = child(plugin, "executions")
                    .map(executionsElement -> children(executionsElement, "execution"))
                    .orElseGet(List::of);
            plugins.add(new MavenPluginInspection(
                    coordinate,
                    phases(groupId, artifactId, executions, properties),
                    goals(executions, properties),
                    disabledExecutions(executions, properties),
                    pluginManagement,
                    MavenExecConfigParser.invocations(artifactId, plugin, executions, properties),
                    MavenExecConfigParser.databaseBacked(artifactId, plugin, executions),
                    conventionalKotlinJvmExecutions(groupId, artifactId, executions, properties),
                    configurationPresent(plugin, executions),
                    activeConfiguredExecutionsPresent(plugin, executions, properties),
                    text(plugin, "extensions")
                            .map(properties::interpolate)
                            .map(String::strip)
                            .orElse(""),
                    child(plugin, "dependencies").isPresent(),
                    kotlinPluginProperties(groupId, artifactId, properties),
                    kaptIncludeCompileClasspath(groupId, artifactId, properties)));
        }
        return plugins;
    }

    private static List<String> phases(
            String groupId,
            String artifactId,
            List<Element> executions,
            MavenPomProperties properties) {
        List<String> phases = new ArrayList<>();
        for (Element execution : executions) {
            Optional<String> phase = text(execution, "phase")
                    .map(properties::interpolate)
                    .map(String::strip)
                    .filter(value -> !value.isBlank());
            if (phase.isPresent()) {
                if (!"none".equalsIgnoreCase(phase.orElseThrow())) {
                    phases.add(phase.orElseThrow());
                }
                continue;
            }
            for (String goal : goals(execution, properties)) {
                defaultPhase(groupId, artifactId, goal).ifPresent(phases::add);
            }
        }
        return phases.stream().distinct().sorted().toList();
    }

    private static List<String> goals(List<Element> executions, MavenPomProperties properties) {
        return executions.stream()
                .flatMap(execution -> goals(execution, properties).stream())
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> goals(Element execution, MavenPomProperties properties) {
        return child(execution, "goals").stream()
                .flatMap(goalsElement -> texts(Optional.of(goalsElement), "goal").stream())
                .map(properties::interpolate)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static List<String> disabledExecutions(
            List<Element> executions,
            MavenPomProperties properties) {
        List<String> disabled = new ArrayList<>();
        for (Element execution : executions) {
            Optional<String> phase = text(execution, "phase")
                    .map(properties::interpolate)
                    .map(String::strip);
            if (phase.isPresent() && "none".equalsIgnoreCase(phase.orElseThrow())) {
                disabled.add(disabledExecutionLabel(execution, properties));
            }
        }
        return disabled.stream().distinct().sorted().toList();
    }

    private static String disabledExecutionLabel(Element execution, MavenPomProperties properties) {
        Optional<String> id = text(execution, "id")
                .map(properties::interpolate)
                .map(String::strip)
                .filter(value -> !value.isBlank());
        if (id.isPresent()) {
            return id.orElseThrow();
        }
        List<String> goals = goals(execution, properties);
        return goals.isEmpty() ? "execution" : String.join(",", goals);
    }

    private static Optional<String> defaultPhase(String groupId, String artifactId, String goal) {
        String plugin = artifactId.toLowerCase();
        String normalizedGoal = goal.toLowerCase();
        if (groupId.equalsIgnoreCase("org.codehaus.gmavenplus") && plugin.equals("gmavenplus-plugin")) {
            if (normalizedGoal.equals("compile")) {
                return Optional.of("compile");
            }
            if (normalizedGoal.equals("compiletests")) {
                return Optional.of("test-compile");
            }
        }
        if (plugin.equals("maven-surefire-plugin") && normalizedGoal.equals("test")) {
            return Optional.of("test");
        }
        if (plugin.equals("maven-failsafe-plugin")) {
            if (normalizedGoal.equals("integration-test")) {
                return Optional.of("integration-test");
            }
            if (normalizedGoal.equals("verify")) {
                return Optional.of("verify");
            }
        }
        if (plugin.equals("maven-compiler-plugin")) {
            if (normalizedGoal.equals("compile")) {
                return Optional.of("compile");
            }
            if (normalizedGoal.equals("testcompile") || normalizedGoal.equals("test-compile")) {
                return Optional.of("test-compile");
            }
        }
        if (groupId.equalsIgnoreCase("org.jetbrains.kotlin")
                && plugin.equals("kotlin-maven-plugin")) {
            if (normalizedGoal.equals("compile")) {
                return Optional.of("compile");
            }
            if (normalizedGoal.equals("test-compile")) {
                return Optional.of("test-compile");
            }
        }
        if (plugin.equals("spring-boot-maven-plugin") && normalizedGoal.equals("repackage")) {
            return Optional.of("package");
        }
        if (codeGenerationPlugin(plugin, normalizedGoal)) {
            return Optional.of("generate-sources");
        }
        return Optional.empty();
    }

    private static boolean conventionalKotlinJvmExecutions(
            String groupId,
            String artifactId,
            List<Element> executions,
            MavenPomProperties properties) {
        if (!groupId.equalsIgnoreCase("org.jetbrains.kotlin")
                || !artifactId.equalsIgnoreCase("kotlin-maven-plugin")
                || executions.isEmpty()) {
            return false;
        }
        Set<String> seenGoals = new LinkedHashSet<>();
        for (Element execution : executions) {
            List<String> goals = goals(execution, properties).stream()
                    .map(goal -> goal.toLowerCase(Locale.ROOT))
                    .toList();
            if (goals.isEmpty()) {
                return false;
            }
            Optional<String> declaredPhase = text(execution, "phase")
                    .map(properties::interpolate)
                    .map(String::strip)
                    .filter(value -> !value.isBlank())
                    .map(value -> value.toLowerCase(Locale.ROOT));
            if (declaredPhase.filter("none"::equals).isPresent()) {
                return false;
            }
            for (String goal : goals) {
                String expectedPhase = switch (goal) {
                    case "compile" -> "compile";
                    case "test-compile" -> "test-compile";
                    default -> null;
                };
                if (expectedPhase == null
                        || !expectedPhase.equals(declaredPhase.orElse(expectedPhase))
                        || !seenGoals.add(goal)) {
                    return false;
                }
            }
        }
        return !seenGoals.isEmpty();
    }

    private static List<String> kotlinPluginProperties(
            String groupId,
            String artifactId,
            MavenPomProperties properties) {
        if (!groupId.equalsIgnoreCase("org.jetbrains.kotlin")
                || !artifactId.equalsIgnoreCase("kotlin-maven-plugin")) {
            return List.of();
        }
        return properties.values().keySet().stream()
                .map(String::strip)
                .filter(name -> name.startsWith("kotlin."))
                .filter(name -> !name.equals("kotlin.version"))
                .distinct()
                .sorted()
                .toList();
    }

    private static String kaptIncludeCompileClasspath(
            String groupId,
            String artifactId,
            MavenPomProperties properties) {
        if (!groupId.equals("org.jetbrains.kotlin")
                || !artifactId.equals("kotlin-maven-plugin")) {
            return "";
        }
        return properties.interpolate(properties.values().get("kapt.include.compile.classpath")).strip();
    }

    private static boolean configurationPresent(Element plugin, List<Element> executions) {
        return child(plugin, "configuration").isPresent()
                || executions.stream().anyMatch(execution ->
                        child(execution, "configuration").isPresent());
    }

    private static boolean activeConfiguredExecutionsPresent(
            Element plugin,
            List<Element> executions,
            MavenPomProperties properties) {
        boolean pluginConfiguration = child(plugin, "configuration").isPresent();
        return executions.stream()
                .filter(execution -> text(execution, "phase")
                        .map(properties::interpolate)
                        .map(String::strip)
                        .filter("none"::equalsIgnoreCase)
                        .isEmpty())
                .anyMatch(execution -> pluginConfiguration
                        || child(execution, "configuration").isPresent());
    }

    private static boolean codeGenerationPlugin(String plugin, String goal) {
        if (List.of(
                "antlr4-maven-plugin",
                "javacc-maven-plugin",
                "ph-javacc-maven-plugin",
                "openapi-generator-maven-plugin",
                "protobuf-maven-plugin",
                "jaxb2-maven-plugin",
                "jaxb-maven-plugin").contains(plugin)) {
            return true;
        }
        return goal.equals("generate")
                || goal.equals("generate-sources")
                || goal.equals("antlr4")
                || goal.equals("javacc");
    }
}
