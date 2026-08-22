package sh.zolt.explain.emit;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.gradle.GradleDependencyInspection;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import java.util.List;

/**
 * Routes a Gradle project's dependencies into the drafted authored lanes. A {@code platform(...)}
 * import lands in {@code [platforms]}; a version-less dependency in a build file that imports a
 * platform becomes platform-managed {@code { managed = true }}; a {@code project(":lib")} edge becomes
 * a {@code { workspace = true }} member reference. Mirrors {@link MavenDependencySectionMapper}.
 */
final class GradleDependencySectionMapper {
    private final DraftDependencies dependencies;
    private final WorkspaceMemberRegistry registry;
    private final List<String> notes;

    GradleDependencySectionMapper(
            DraftDependencies dependencies, WorkspaceMemberRegistry registry, List<String> notes) {
        this.dependencies = dependencies;
        this.registry = registry;
        this.notes = notes;
    }

    void map(List<GradleDependencyInspection> declared) {
        // A platform(...) import is lane-agnostic in Zolt: route it to [platforms] first, then let the
        // presence of a platform decide whether a version-less dependency is emitted as managed.
        for (GradleDependencyInspection dependency : declared) {
            if (dependency.isPlatform()) {
                mapPlatform(dependency);
            }
        }
        for (GradleDependencyInspection dependency : declared) {
            if (dependency.isPlatform()) {
                continue;
            }
            if (mapWorkspaceDependency(dependency)) {
                continue;
            }
            mapDependency(dependency);
        }
    }

    /**
     * Routes a {@code platform(...)} / {@code enforcedPlatform(...)} import to {@code [platforms]}.
     * {@code enforcedPlatform} maps like a platform plus a review note, because Gradle's enforced
     * semantics (the BOM's versions override transitive versions) are only approximated by a Zolt
     * platform; the honest analog for a hard pin is a {@code [dependencies.constraints]} strict entry,
     * which this draft points at rather than auto-generates.
     */
    private void mapPlatform(GradleDependencyInspection dependency) {
        String resolved = dependency.resolvedCoordinate();
        if (resolved == null || resolved.isBlank()) {
            if (dependency.versionCatalogAlias() != null && !dependency.versionCatalogAlias().isBlank()) {
                notes.add(
                        "Gradle platform import in `" + dependency.configuration() + "` uses version-catalog"
                                + " alias `" + dependency.versionCatalogAlias() + "` with no resolved coordinate;"
                                + " look it up in libs.versions.toml and add it under [platforms] by hand.");
            } else {
                notes.add(
                        "Gradle platform import `" + dependency.notation() + "` in `"
                                + dependency.configuration() + "` could not be resolved to a coordinate;"
                                + " add it under [platforms] by hand after confirming the group:name:version.");
            }
            return;
        }
        String coordinate = GradleInspectionMapper.coordinateOf(resolved);
        String version = GradleInspectionMapper.versionOf(resolved);
        if (version == null) {
            notes.add(
                    "Gradle platform import `" + coordinate + "` in `" + dependency.configuration()
                            + "` has no version in its resolved coordinate; add one under [platforms] before"
                            + " resolving.");
            return;
        }
        dependencies.platform(coordinate, version);
        if (dependency.platformKind() == GradleDependencyInspection.PlatformKind.ENFORCED_PLATFORM) {
            notes.add(
                    "Gradle `enforcedPlatform(" + coordinate + ")` was mapped to [platforms]. Gradle's"
                            + " enforced semantics (forcing the BOM's managed versions over transitive"
                            + " versions) are only approximated; if you must hard-pin, add a"
                            + " [dependencies.constraints] entry per coordinate. This draft does not"
                            + " auto-generate those constraints.");
        }
    }

    /**
     * Rewrites a {@code project(":lib")} edge to {@code { workspace = true }}. Returns true when the
     * dependency was a project edge (recorded or noted), false otherwise.
     */
    private boolean mapWorkspaceDependency(GradleDependencyInspection dependency) {
        String projectPath = projectPath(dependency.notation());
        if (projectPath == null) {
            return false;
        }
        WorkspaceMemberRegistry.Member member = registry == null ? null : registry.memberFor(projectPath);
        if (member == null) {
            notes.add(
                    "Gradle dependency `project(\"" + dependency.notation() + "\")` in `"
                            + dependency.configuration() + "` targets a project outside this workspace;"
                            + " wire it by hand.");
            return true;
        }
        DependencyLane lane = lane(dependency.configuration());
        if (lane == null || lane == DependencyLane.RUNTIME || lane == DependencyLane.PROVIDED) {
            notes.add(
                    "Gradle dependency `project(\"" + dependency.notation() + "\")` in `"
                            + dependency.configuration() + "` maps to sibling module `" + member.path()
                            + "`, but that configuration has no direct workspace lane; wire it by hand.");
            return true;
        }
        dependencies.workspaceMember(lane, member.coordinate());
        return true;
    }

    /**
     * The Gradle project path a {@code project(":a:b")} notation refers to, normalized to a workspace
     * directory path ({@code a/b}); {@code null} when the notation is not a project reference.
     */
    private static String projectPath(String notation) {
        if (notation == null || !notation.startsWith(":")) {
            return null;
        }
        String path = notation.replaceFirst("^:+", "").replace(':', '/').strip();
        return path.isBlank() ? null : path;
    }

    private void mapDependency(GradleDependencyInspection dependency) {
        String resolved = dependency.resolvedCoordinate();
        if (resolved == null || resolved.isBlank()) {
            if (dependency.versionCatalogAlias() != null && !dependency.versionCatalogAlias().isBlank()) {
                notes.add(
                        "Gradle dependency in `" + dependency.configuration() + "` uses version-catalog"
                                + " alias `" + dependency.versionCatalogAlias() + "` with no resolved"
                                + " coordinate; look it up in libs.versions.toml and add it by hand.");
            } else {
                notes.add(
                        "Gradle dependency notation `" + dependency.notation() + "` in `"
                                + dependency.configuration() + "` could not be resolved to a coordinate;"
                                + " add it by hand after confirming the group:name:version.");
            }
            return;
        }
        String coordinate = GradleInspectionMapper.coordinateOf(resolved);
        String version = GradleInspectionMapper.versionOf(resolved);
        DependencyLane lane = lane(dependency.configuration());
        if (version == null) {
            if (dependencies.hasPlatform() && lane != null) {
                dependencies.managed(lane, coordinate, AuthoredDependencyMetadata.none());
                notes.add(
                        "Gradle dependency `" + coordinate + "` in `" + dependency.configuration()
                                + "` has no version and is emitted as platform-managed; verify a declared"
                                + " platform manages this coordinate before resolving.");
                return;
            }
            notes.add(
                    "Gradle dependency `" + coordinate + "` in `" + dependency.configuration()
                            + "` has no version in its resolved coordinate; add one before resolving.");
            return;
        }
        if (lane == null) {
            notes.add(
                    "Gradle configuration `" + dependency.configuration() + "` for `" + coordinate
                            + "` has no direct Zolt lane; place it manually after review.");
            return;
        }
        dependencies.fixed(lane, coordinate, version);
    }

    private static DependencyLane lane(String configuration) {
        return switch (configuration) {
            case "api", "compileOnlyApi" -> DependencyLane.API;
            case "implementation", "compile" -> DependencyLane.IMPLEMENTATION;
            case "runtimeOnly", "runtime" -> DependencyLane.RUNTIME;
            case "compileOnly", "providedCompile" -> DependencyLane.PROVIDED;
            case "testImplementation", "testRuntimeOnly", "testCompile", "testCompileOnly" ->
                    DependencyLane.TEST;
            default -> null;
        };
    }
}
