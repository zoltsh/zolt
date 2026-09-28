package sh.zolt.explain.maven;

import static sh.zolt.explain.maven.MavenXml.child;
import static sh.zolt.explain.maven.MavenXml.children;
import static sh.zolt.explain.maven.MavenXml.hasName;
import static sh.zolt.explain.maven.MavenXml.name;
import static sh.zolt.explain.maven.MavenXml.text;
import static sh.zolt.explain.maven.MavenXml.texts;

import sh.zolt.explain.MigrationExplainException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Element;

final class MavenProjectInspectionBuilder {
    private MavenProjectInspectionBuilder() {
    }

    static MavenProjectInspection build(
            Path pom,
            Path relativePath,
            Path projectDirectory,
            Element project,
            MavenReactorPoms reactor,
            RecoveredParentMetadata recovered) {
        if (project == null || !hasName(project, "project")) {
            throw new MigrationExplainException(
                    "Could not inspect Maven project. Expected root <project> element in " + pom + ".");
        }

        // The parent chain within the reactor, nearest parent first (empty for the root or an external
        // parent). Effective properties and dependencyManagement inherit root-first with child override.
        List<Element> ancestors = reactor.ancestors(project);
        MavenPomProperties properties = effectiveProperties(project, projectDirectory, ancestors, recovered);
        List<String> modules = texts(child(project, "modules"), "module");
        // A module's own dependencyManagement layered over its inherited (parent-chain) management, plus any
        // recovered external-parent management as the farthest layer, so a version-less dependency can pick
        // up a version managed by a reactor parent or a fetched external parent.
        MavenManagedVersions managedVersions = MavenManagedVersions.resolve(
                project, ancestors, properties, recovered.managedDependencies());
        List<MavenDependencyInspection> dependencies = managedVersions.applyTo(
                MavenDependencyParser.parseDependencies(child(project, "dependencies"), false, properties));
        List<MavenDependencyInspection> effectiveDependencyManagement = managedVersions.effectiveDependencyManagement();
        List<MavenDependencyInspection> dependencyManagement = effectiveDependencyManagement.stream()
                .filter(dependency -> !dependency.importedBom())
                .toList();
        List<MavenDependencyInspection> importedBoms = effectiveDependencyManagement.stream()
                .filter(MavenDependencyInspection::importedBom)
                .toList();
        List<MavenAnnotationProcessorInspection> annotationProcessors =
                MavenAnnotationProcessorPaths.parse(project, properties);
        List<MavenParentInspection> parents = parents(project, ancestors, reactor, recovered.resolved());
        List<MavenRepositoryInspection> repositories = repositories(project);
        List<MavenPluginInspection> plugins = MavenPluginParser.parse(project, properties);
        List<MavenProfileInspection> profiles = profiles(project);
        MavenCompilerJavaVersions javaVersions = MavenCompilerJavaVersions.inspect(project, properties);

        return new MavenProjectInspection(
                relativePath,
                artifactId(project, projectDirectory),
                groupId(project, properties),
                projectVersion(project, properties),
                projectName(project, properties),
                text(project, "packaging").orElse("jar"),
                javaVersions.mainVersion(),
                javaVersions.testVersion(),
                javaVersions.mainProvenance(),
                modules,
                MavenRootInspection.sourceRoots(
                        project,
                        projectDirectory,
                        "sourceDirectory",
                        "src/main/java",
                        properties,
                        List.of("src/main/groovy"),
                        MavenRootInspection.buildHelperSourceRoots(project, properties)),
                MavenRootInspection.sourceRoots(
                        project,
                        projectDirectory,
                        "testSourceDirectory",
                        "src/test/java",
                        properties,
                        List.of()),
                MavenRootInspection.resourceRoots(project, projectDirectory, "resources", "src/main/resources", properties),
                MavenRootInspection.resourceRoots(
                        project,
                        projectDirectory,
                        "testResources",
                        "src/test/resources",
                        properties),
                dependencies,
                dependencyManagement,
                importedBoms,
                annotationProcessors,
                parents,
                repositories,
                plugins,
                profiles);
    }

    private static List<MavenParentInspection> parents(
            Element project,
            List<Element> ancestors,
            MavenReactorPoms reactor,
            boolean externalParentRecovered) {
        List<MavenParentInspection> parents = new ArrayList<>();
        Element current = project;
        int ancestorIndex = 0;
        while (true) {
            Optional<Element> parentElement = child(current, "parent");
            if (parentElement.isEmpty()) {
                return parents;
            }
            String groupId = text(parentElement.orElseThrow(), "groupId").orElse("");
            String artifactId = text(parentElement.orElseThrow(), "artifactId").orElse("");
            String version = text(parentElement.orElseThrow(), "version").orElse("");
            Optional<Element> resolved = Optional.empty();
            if (!groupId.isBlank()
                    && !artifactId.isBlank()
                    && !version.isBlank()
                    && ancestorIndex < ancestors.size()) {
                Element candidate = ancestors.get(ancestorIndex);
                if (reactor.sameCoordinate(candidate, groupId, artifactId, version)) {
                    resolved = Optional.of(candidate);
                }
            }
            if (resolved.isEmpty()) {
                // The chain left the reactor. When --resolve-external-parents fetched this parent, it is
                // resolved-but-external (inReactor = false, resolved = true) so inherited metadata counts.
                parents.add(new MavenParentInspection(
                        groupId, artifactId, version, false, externalParentRecovered, ""));
                return parents;
            }
            Element ancestor = resolved.orElseThrow();
            parents.add(new MavenParentInspection(
                    groupId,
                    artifactId,
                    version,
                    true,
                    true,
                    reactor.relativePath(ancestor).toString()));
            current = ancestor;
            ancestorIndex++;
        }
    }

    private static List<MavenRepositoryInspection> repositories(Element project) {
        List<MavenRepositoryInspection> repositories = new ArrayList<>();
        repositories.addAll(repositoryList(child(project, "repositories"), false));
        repositories.addAll(repositoryList(child(project, "pluginRepositories"), true));
        repositories.sort(Comparator
                .comparing(MavenRepositoryInspection::pluginRepository)
                .thenComparing(MavenRepositoryInspection::id)
                .thenComparing(MavenRepositoryInspection::url));
        return repositories;
    }

    private static List<MavenRepositoryInspection> repositoryList(
            Optional<Element> repositoriesElement,
            boolean pluginRepository) {
        if (repositoriesElement.isEmpty()) {
            return List.of();
        }
        List<MavenRepositoryInspection> repositories = new ArrayList<>();
        String elementName = pluginRepository ? "pluginRepository" : "repository";
        for (Element repository : children(repositoriesElement.orElseThrow(), elementName)) {
            repositories.add(new MavenRepositoryInspection(
                    text(repository, "id").orElse("unknown"),
                    text(repository, "url").orElse(""),
                    pluginRepository,
                    snapshotsEnabled(repository)));
        }
        return repositories;
    }

    private static boolean snapshotsEnabled(Element repository) {
        return child(repository, "snapshots")
                .flatMap(snapshots -> text(snapshots, "enabled"))
                .map("true"::equalsIgnoreCase)
                .orElse(false);
    }

    private static List<MavenProfileInspection> profiles(Element project) {
        Optional<Element> profilesElement = child(project, "profiles");
        if (profilesElement.isEmpty()) {
            return List.of();
        }
        List<MavenProfileInspection> profiles = new ArrayList<>();
        for (Element profile : children(profilesElement.orElseThrow(), "profile")) {
            profiles.add(new MavenProfileInspection(
                    text(profile, "id").orElse("unknown"),
                    activationHints(profile),
                    texts(child(profile, "modules"), "module")));
        }
        profiles.sort(Comparator.comparing(MavenProfileInspection::id));
        return profiles;
    }

    private static List<String> activationHints(Element profile) {
        Optional<Element> activation = child(profile, "activation");
        if (activation.isEmpty()) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        text(activation.orElseThrow(), "jdk").ifPresent(value -> hints.add("jdk " + value));
        child(activation.orElseThrow(), "property").ifPresent(property -> {
            text(property, "name").ifPresent(value -> hints.add("property " + value));
            text(property, "value").ifPresent(value -> hints.add("property value " + value));
        });
        child(activation.orElseThrow(), "os").ifPresent(ignored -> hints.add("operating system"));
        child(activation.orElseThrow(), "file").ifPresent(ignored -> hints.add("file presence"));
        if (hints.isEmpty()) {
            hints.add("activation block");
        }
        hints.sort(String::compareTo);
        return hints;
    }

    private static Map<String, String> parseProperties(Element project) {
        Optional<Element> properties = child(project, "properties");
        if (properties.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Element property : children(properties.orElseThrow())) {
            values.put(name(property), property.getTextContent().trim());
        }
        return values;
    }

    /**
     * The properties used to interpolate {@code ${...}} references. Layers the POM's own
     * {@code <properties>} over the standard project built-ins ({@code project.groupId},
     * {@code project.version}, {@code project.artifactId}, and their {@code parent.*} forms) that
     * Maven itself exposes, mirroring {@code PomPropertyInterpolator} in zolt-repository. Parent
     * coordinates come from {@code <parent>} so a child that omits its own coordinate still resolves
     * {@code ${project.version}} against the inherited value.
     *
     * <p>{@code <properties>} declared by reactor ancestors are layered root-first so a nearer POM's
     * value overrides a farther one and the module's own {@code <properties>} win last, mirroring Maven's
     * inheritance. This lets a child resolve a version property (or Java version) declared only by its
     * reactor parent.
     */
    private static MavenPomProperties effectiveProperties(
            Element project,
            Path projectDirectory,
            List<Element> ancestors,
            RecoveredParentMetadata recovered) {
        Map<String, String> properties = new LinkedHashMap<>();
        Optional<Element> parent = child(project, "parent");
        String parentGroupId = parent.flatMap(element -> text(element, "groupId")).orElse(null);
        String parentArtifactId = parent.flatMap(element -> text(element, "artifactId")).orElse(null);
        String parentVersion = parent.flatMap(element -> text(element, "version")).orElse(null);

        String groupId = text(project, "groupId").orElse(parentGroupId);
        String artifactId = text(project, "artifactId").orElse(projectDirectory.getFileName().toString());
        String version = text(project, "version").orElse(parentVersion);

        putBuiltIn(properties, "project.groupId", groupId);
        putBuiltIn(properties, "pom.groupId", groupId);
        putBuiltIn(properties, "groupId", groupId);
        putBuiltIn(properties, "project.artifactId", artifactId);
        putBuiltIn(properties, "pom.artifactId", artifactId);
        putBuiltIn(properties, "artifactId", artifactId);
        putBuiltIn(properties, "project.version", version);
        putBuiltIn(properties, "pom.version", version);
        putBuiltIn(properties, "version", version);
        putBuiltIn(properties, "project.parent.groupId", parentGroupId);
        putBuiltIn(properties, "parent.groupId", parentGroupId);
        putBuiltIn(properties, "project.parent.artifactId", parentArtifactId);
        putBuiltIn(properties, "parent.artifactId", parentArtifactId);
        putBuiltIn(properties, "project.parent.version", parentVersion);
        putBuiltIn(properties, "parent.version", parentVersion);

        // Recovered external-parent <properties> sit below every reactor ancestor (they are the farthest
        // POMs in the chain) but above the built-ins, so a version property declared only by a fetched
        // external parent resolves while a nearer reactor or module value still wins.
        properties.putAll(recovered.properties());

        // Inherited <properties>, farthest ancestor first, then the module itself: nearer wins on
        // collision and POM-declared properties win over the built-ins.
        List<Element> rootFirst = new ArrayList<>(ancestors);
        Collections.reverse(rootFirst);
        rootFirst.add(project);
        for (Element pom : rootFirst) {
            properties.putAll(parseProperties(pom));
        }
        return new MavenPomProperties(properties);
    }

    private static void putBuiltIn(Map<String, String> properties, String key, String value) {
        if (value != null && !value.isBlank()) {
            properties.put(key, value);
        }
    }

    private static String artifactId(Element project, Path projectDirectory) {
        return text(project, "artifactId")
                .orElseGet(() -> projectDirectory.getFileName().toString());
    }

    /** The project groupId, inheriting from {@code <parent>} when omitted; blank when unresolvable. */
    private static String groupId(Element project, MavenPomProperties properties) {
        return resolvedCoordinate(project, "groupId", properties);
    }

    /** The project version, inheriting from {@code <parent>} when omitted; blank when unresolvable. */
    private static String projectVersion(Element project, MavenPomProperties properties) {
        return resolvedCoordinate(project, "version", properties);
    }

    private static String resolvedCoordinate(Element project, String field, MavenPomProperties properties) {
        return text(project, field)
                .or(() -> child(project, "parent").flatMap(parent -> text(parent, field)))
                .map(properties::interpolate)
                .filter(value -> !value.isBlank() && !value.contains("${"))
                .orElse("");
    }

    private static String projectName(Element project, MavenPomProperties properties) {
        return text(project, "name")
                .map(properties::interpolate)
                .orElse("");
    }
}
