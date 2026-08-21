package sh.zolt.explain.emit;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.maven.MavenDependencyExclusion;
import sh.zolt.explain.maven.MavenDependencyInspection;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routes a Maven project's dependencies into the drafted authored dependency lanes: {@code compile}
 * to implementation, {@code runtime} to runtime, {@code provided} to provided, {@code test} to test.
 * A dependency with no static version becomes platform-managed when the draft imports a platform, a
 * sibling reactor module becomes a {@code { workspace = true }} member reference, and anything else
 * becomes a review note.
 */
final class MavenDependencySectionMapper {
    private final WorkspaceMemberRegistry registry;
    private final DraftDependencies dependencies;
    private final Map<String, String> managedPins;
    private final List<String> notes;

    MavenDependencySectionMapper(
            WorkspaceMemberRegistry registry,
            DraftDependencies dependencies,
            Map<String, String> managedPins,
            List<String> notes) {
        this.registry = registry;
        this.dependencies = dependencies;
        this.managedPins = managedPins == null ? Map.of() : Map.copyOf(managedPins);
        this.notes = notes;
    }

    void map(MavenDependencyInspection dependency) {
        String coordinate = coordinateOf(dependency.coordinate());
        if (!dependency.classifier().isBlank()) {
            notes.add(
                    "Dependency `" + coordinate + "` (scope " + dependency.scope()
                            + ") declares Maven classifier `" + dependency.classifier()
                            + "`. Confirm the artifact variant and add `classifier` to the entry by hand"
                            + " before resolving.");
            return;
        }
        if (mapWorkspaceDependency(dependency, coordinate)) {
            return;
        }
        DependencyLane lane = lane(dependency.scope());
        String version = dependency.version();
        if (version.isBlank()) {
            String pinnedVersion = managedPins.get(coordinate);
            if (pinnedVersion != null) {
                if (lane == null) {
                    noteUnmappedScope(dependency, coordinate);
                    return;
                }
                dependencies.fixed(lane, coordinate, pinnedVersion, metadata(dependency));
                return;
            }
            if (dependencies.hasPlatform() && lane != null) {
                dependencies.managed(lane, coordinate, metadata(dependency));
                return;
            }
            if (lane == null) {
                noteUnmappedScope(dependency, coordinate);
                return;
            }
            notes.add(
                    "Dependency `" + coordinate + "` (scope " + dependency.scope() + ") has no static"
                            + " version; it is likely managed by a BOM. Add a version or platform entry"
                            + " before resolving.");
            return;
        }
        if (version.contains("${")) {
            notes.add(
                    "Dependency `" + coordinate + "` (scope " + dependency.scope() + ") uses version `"
                            + version + "`, which references a property the static audit could not"
                            + " resolve. Replace it with a fixed version before resolving.");
            return;
        }
        if (lane == null) {
            noteUnmappedScope(dependency, coordinate);
            return;
        }
        dependencies.fixed(lane, coordinate, version, metadata(dependency));
    }

    private void noteUnmappedScope(MavenDependencyInspection dependency, String coordinate) {
        notes.add(
                "Dependency `" + coordinate + "` uses Maven scope `" + dependency.scope()
                        + "`, which has no direct Zolt lane; place it manually after review.");
    }

    private static DependencyLane lane(String scope) {
        return switch (scope) {
            case "compile" -> DependencyLane.IMPLEMENTATION;
            case "runtime" -> DependencyLane.RUNTIME;
            case "provided" -> DependencyLane.PROVIDED;
            case "test" -> DependencyLane.TEST;
            default -> null;
        };
    }

    private boolean mapWorkspaceDependency(MavenDependencyInspection dependency, String coordinate) {
        if (registry == null) {
            return false;
        }
        String memberPath = registry.pathFor(coordinate);
        if (memberPath == null) {
            return false;
        }
        switch (dependency.scope()) {
            case "compile" -> dependencies.workspaceMember(DependencyLane.IMPLEMENTATION, coordinate);
            case "test" -> dependencies.workspaceMember(DependencyLane.TEST, coordinate);
            default -> notes.add(
                    "Dependency `" + coordinate + "` targets sibling module `" + memberPath
                            + "` in Maven scope `" + dependency.scope() + "`, which Zolt cannot express"
                            + " as a workspace edge; wire it under the matching lane by hand.");
        }
        return true;
    }

    /**
     * Exclusions carried across as exact coordinates. Maven's wildcard exclusion forms have no exact
     * manifest spelling, so they are reported rather than approximated.
     */
    private AuthoredDependencyMetadata metadata(MavenDependencyInspection dependency) {
        if (dependency.exclusions().isEmpty()) {
            return AuthoredDependencyMetadata.none();
        }
        List<DependencyCoordinate> exclusions = new ArrayList<>();
        for (MavenDependencyExclusion exclusion : dependency.exclusions()) {
            String value = exclusion.groupId() + ":" + exclusion.artifactId();
            try {
                exclusions.add(new DependencyCoordinate(value));
            } catch (IllegalArgumentException failure) {
                notes.add("Dependency `" + coordinateOf(dependency.coordinate()) + "` excludes `" + value
                        + "`, which is not an exact `group:artifact` coordinate; Zolt exclusions name one"
                        + " coordinate each, so add the real exclusions by hand.");
            }
        }
        if (exclusions.isEmpty()) {
            return AuthoredDependencyMetadata.none();
        }
        return new AuthoredDependencyMetadata(
                false, false, Optional.empty(), Optional.empty(), exclusions);
    }

    private static String coordinateOf(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length >= 2) {
            return parts[0] + ":" + parts[1];
        }
        return coordinate;
    }
}
