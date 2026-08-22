package sh.zolt.manifest.adapter;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyMetadata;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.DependencyExclusionSpec;
import sh.zolt.project.DependencyMetadata;

/**
 * Projects final {@code [dependencies.*]} declarations onto the legacy per-lane versioned map,
 * managed set, and workspace map triple that {@link sh.zolt.project.ProjectConfig} exposes.
 */
final class ProjectConfigDependencyLanes {
    private final Map<DependencyLane, Lane> lanes = new EnumMap<>(DependencyLane.class);
    private final Map<String, DependencyMetadata> metadata = new LinkedHashMap<>();

    private ProjectConfigDependencyLanes() {
        for (DependencyLane lane : DependencyLane.values()) {
            lanes.put(lane, new Lane());
        }
    }

    /**
     * Adapts every authored declaration.
     *
     * @param dependencies the authored declaration set, absent when the domain is unauthored
     * @param versions effective {@code [versions]} aliases used to resolve {@code versionRef}
     * @param workspacePaths resolved provider member path per workspace-selector coordinate
     * @param seededMetadata metadata already recorded by earlier sections, notably {@code platforms}
     */
    static ProjectConfigDependencyLanes adapt(
            Optional<AuthoredDependencies> dependencies,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            Map<DependencyCoordinate, String> workspacePaths,
            Map<String, DependencyMetadata> seededMetadata) {
        ProjectConfigDependencyLanes adapted = new ProjectConfigDependencyLanes();
        adapted.metadata.putAll(seededMetadata);
        List<AuthoredDependency> declarations = dependencies
                .map(AuthoredDependencies::declarations)
                .orElseGet(List::of);
        for (AuthoredDependency declaration : orderedByLane(declarations)) {
            adapted.add(declaration, versions, workspacePaths);
        }
        return adapted;
    }

    Map<String, String> versioned(DependencyLane lane) {
        return Map.copyOf(lanes.get(lane).versioned);
    }

    Set<String> managed(DependencyLane lane) {
        return Set.copyOf(lanes.get(lane).managed);
    }

    Map<String, String> workspace(DependencyLane lane) {
        return Map.copyOf(lanes.get(lane).workspace);
    }

    Map<String, DependencyMetadata> metadata() {
        return Map.copyOf(metadata);
    }

    private void add(
            AuthoredDependency declaration,
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            Map<DependencyCoordinate, String> workspacePaths) {
        String section = LegacyDependencySection.of(declaration.lane());
        String coordinate = declaration.coordinate().value();
        Lane lane = lanes.get(declaration.lane());
        AuthoredDependencyMetadata authored = declaration.metadata();
        boolean publishOnly = authored.publishOnly();

        String version = null;
        String versionRef = null;
        boolean managed = false;
        String workspacePath = null;
        switch (declaration.selector()) {
            case DependencySelector.FixedVersion fixed -> {
                version = fixed.value();
                if (!publishOnly) {
                    lane.versioned.put(coordinate, version);
                }
            }
            case DependencySelector.VersionReference reference -> {
                versionRef = reference.alias().value();
                version = aliasValue(versions, reference.alias(), section, coordinate);
                if (!publishOnly) {
                    lane.versioned.put(coordinate, version);
                }
            }
            case DependencySelector.Managed ignored -> {
                managed = true;
                if (!publishOnly) {
                    lane.managed.add(coordinate);
                }
            }
            case DependencySelector.Workspace ignored -> {
                requireProjectableWorkspaceLane(declaration.lane(), coordinate);
                workspacePath = workspacePath(workspacePaths, declaration.coordinate(), section);
                lane.workspace.put(coordinate, workspacePath);
            }
        }

        DependencyMetadata adapted = new DependencyMetadata(
                section,
                coordinate,
                version,
                versionRef,
                managed,
                workspacePath,
                authored.optional(),
                publishOnly,
                exclusions(authored),
                authored.classifier().orElse(null),
                authored.type().orElse(null));
        if (!adapted.emptyMetadata() || publishOnly) {
            metadata.put(DependencyMetadata.key(section, coordinate), adapted);
        }
    }

    private static List<DependencyExclusionSpec> exclusions(AuthoredDependencyMetadata metadata) {
        return metadata.exclusions().stream()
                .map(exclusion -> new DependencyExclusionSpec(exclusion.group(), exclusion.artifact()))
                .toList();
    }

    private static String aliasValue(
            Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
            LocalId alias,
            String section,
            String coordinate) {
        EffectiveValue<VersionAliasValue> value = versions.get(alias);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Dependency `" + coordinate + "` in [" + DependencyMetadata.manifestSection(section)
                            + "] references undefined version alias `" + alias + "`.");
        }
        return value.value().value();
    }

    /**
     * Fails closed on a workspace selector in a lane the legacy {@link sh.zolt.project.ProjectConfig}
     * cannot carry. Design §9.5 permits {@code workspace = true} in every lane, but the legacy record
     * has workspace maps only for the API, implementation, test, and both annotation-processor lanes,
     * and the legacy project-edge model has no runtime, provided, or dev scope. Dropping such an edge
     * silently would remove a real dependency, so the boundary rejects it until the legacy record dies.
     */
    private static void requireProjectableWorkspaceLane(DependencyLane lane, String coordinate) {
        if (lane == DependencyLane.RUNTIME || lane == DependencyLane.PROVIDED || lane == DependencyLane.DEV) {
            throw new IllegalArgumentException(
                    "Workspace dependency `" + coordinate + "` in the " + lane
                            + " lane cannot be projected onto the current engine model, which carries "
                            + "workspace members only in the implementation, api, test, processor, and "
                            + "test-processor lanes.");
        }
    }

    private static String workspacePath(
            Map<DependencyCoordinate, String> workspacePaths,
            DependencyCoordinate coordinate,
            String section) {
        String path = workspacePaths.get(coordinate);
        if (path == null) {
            throw new IllegalArgumentException(
                    "Workspace dependency `" + coordinate + "` in ["
                            + DependencyMetadata.manifestSection(section)
                            + "] has no resolved workspace member. A standalone project cannot declare "
                            + "`workspace = true`.");
        }
        return path;
    }

    private static List<AuthoredDependency> orderedByLane(List<AuthoredDependency> declarations) {
        return declarations.stream()
                .sorted((left, right) -> {
                    int byLane = Integer.compare(
                            legacyOrder(left.lane()), legacyOrder(right.lane()));
                    return byLane != 0 ? byLane : left.variant().compareTo(right.variant());
                })
                .toList();
    }

    /**
     * The pre-cut dependency-section visit order, which the engine's {@code dependencyMetadata}
     * map preserves as insertion order.
     */
    private static int legacyOrder(DependencyLane lane) {
        return switch (lane) {
            case API -> 0;
            case IMPLEMENTATION -> 1;
            case RUNTIME -> 2;
            case PROVIDED -> 3;
            case DEV -> 4;
            case PROCESSOR -> 5;
            case TEST -> 6;
            case TEST_PROCESSOR -> 7;
        };
    }

    private static final class Lane {
        private final Map<String, String> versioned = new LinkedHashMap<>();
        private final Set<String> managed = new LinkedHashSet<>();
        private final Map<String, String> workspace = new LinkedHashMap<>();
    }
}
