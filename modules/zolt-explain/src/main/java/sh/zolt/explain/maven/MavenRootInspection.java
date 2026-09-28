package sh.zolt.explain.maven;

import static sh.zolt.explain.maven.MavenXml.child;
import static sh.zolt.explain.maven.MavenXml.children;
import static sh.zolt.explain.maven.MavenXml.text;
import static sh.zolt.explain.maven.MavenXml.texts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Element;

final class MavenRootInspection {
    private MavenRootInspection() {
    }

    static List<String> sourceRoots(
            Element project,
            Path projectDirectory,
            String elementName,
            String defaultRoot,
            MavenPomProperties properties,
            List<String> additionalRoots) {
        return sourceRoots(
                project,
                projectDirectory,
                elementName,
                defaultRoot,
                properties,
                List.of(),
                additionalRoots);
    }

    static List<String> sourceRoots(
            Element project,
            Path projectDirectory,
            String elementName,
            String defaultRoot,
            MavenPomProperties properties,
            List<String> additionalConventionRoots,
            List<String> additionalRoots) {
        List<String> roots = new ArrayList<>();
        Optional<Element> build = child(project, "build");
        build.flatMap(element -> text(element, elementName))
                .map(properties::interpolate)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .ifPresentOrElse(
                        roots::add,
                        () -> addExistingConventionRoot(roots, projectDirectory, defaultRoot));
        for (String conventionRoot : additionalConventionRoots) {
            addExistingConventionRoot(roots, projectDirectory, conventionRoot);
        }
        roots.addAll(additionalRoots);
        return distinct(roots);
    }

    static List<String> resourceRoots(
            Element project,
            Path projectDirectory,
            String resourcesElementName,
            String defaultRoot,
            MavenPomProperties properties) {
        Optional<Element> build = child(project, "build");
        if (build.isEmpty()) {
            return existingConventionRoot(projectDirectory, defaultRoot);
        }
        Optional<Element> resources = child(build.orElseThrow(), resourcesElementName);
        if (resources.isEmpty()) {
            return existingConventionRoot(projectDirectory, defaultRoot);
        }
        List<String> roots = new ArrayList<>();
        String resourceElementName = "testResources".equals(resourcesElementName) ? "testResource" : "resource";
        for (Element resource : children(resources.orElseThrow(), resourceElementName)) {
            text(resource, "directory")
                    .map(properties::interpolate)
                    .map(String::strip)
                    .filter(value -> !value.isBlank())
                    .ifPresent(roots::add);
        }
        return distinct(roots);
    }

    static List<String> buildHelperSourceRoots(Element project, MavenPomProperties properties) {
        Optional<Element> build = child(project, "build");
        if (build.isEmpty()) {
            return List.of();
        }
        Optional<Element> plugins = child(build.orElseThrow(), "plugins");
        if (plugins.isEmpty()) {
            return List.of();
        }
        List<String> roots = new ArrayList<>();
        for (Element plugin : children(plugins.orElseThrow(), "plugin")) {
            if (!isBuildHelperPlugin(plugin, properties)) {
                continue;
            }
            if (hasGoal(plugin, "add-source", properties)) {
                roots.addAll(configurationSources(plugin, properties));
            }
            child(plugin, "executions").ifPresent(executions -> {
                for (Element execution : children(executions, "execution")) {
                    if (hasGoal(execution, "add-source", properties)) {
                        roots.addAll(configurationSources(execution, properties));
                    }
                }
            });
        }
        return distinct(roots);
    }

    private static boolean isBuildHelperPlugin(Element plugin, MavenPomProperties properties) {
        String groupId = properties.interpolate(text(plugin, "groupId").orElse("org.codehaus.mojo"));
        String artifactId = properties.interpolate(text(plugin, "artifactId").orElse(""));
        return "org.codehaus.mojo".equals(groupId) && "build-helper-maven-plugin".equals(artifactId);
    }

    private static boolean hasGoal(Element element, String goal, MavenPomProperties properties) {
        return child(element, "goals").stream()
                .flatMap(goals -> texts(Optional.of(goals), "goal").stream())
                .map(properties::interpolate)
                .map(String::strip)
                .anyMatch(goal::equals);
    }

    private static List<String> configurationSources(Element element, MavenPomProperties properties) {
        Optional<Element> sources = child(element, "configuration")
                .flatMap(configuration -> child(configuration, "sources"));
        if (sources.isEmpty()) {
            return List.of();
        }
        return texts(sources, "source").stream()
                .map(properties::interpolate)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static void addExistingConventionRoot(List<String> roots, Path projectDirectory, String defaultRoot) {
        if (Files.isDirectory(projectDirectory.resolve(defaultRoot))) {
            roots.add(defaultRoot);
        }
    }

    private static List<String> existingConventionRoot(Path projectDirectory, String defaultRoot) {
        return Files.isDirectory(projectDirectory.resolve(defaultRoot)) ? List.of(defaultRoot) : List.of();
    }

    private static List<String> distinct(List<String> roots) {
        Set<String> distinct = new LinkedHashSet<>(roots);
        return List.copyOf(distinct);
    }
}
