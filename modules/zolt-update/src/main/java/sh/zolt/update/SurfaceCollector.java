package sh.zolt.update;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.VersionStability;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.GeneratedArtifactRequest;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredGeneratedTool;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPlatforms;
import sh.zolt.manifest.authored.AuthoredVersionAliases;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Walks one {@link AuthoredManifest} and enumerates every literal version it declares: version
 * aliases, fixed-version dependencies in every lane, platforms, dependency constraints, BOM pins and
 * imports, and named generated-tool coordinates.
 *
 * <p>Only authored declarations are collected. A versionRef-backed entry reports under its alias, a
 * platform-managed or workspace dependency has no literal to advance, SNAPSHOT literals are ignored,
 * and a built-in tool default has no source span to update, so none of them are targets (design
 * §20.1: one logical value, one source location).
 */
final class SurfaceCollector {

    List<SurfaceRequest> collect(AuthoredManifest manifest) {
        Map<String, SurfaceRequest> requests = new LinkedHashMap<>();
        collectAliases(manifest, requests);
        collectDependencies(manifest, requests);
        collectPlatforms(manifest, requests);
        collectConstraints(manifest, requests);
        collectBom(manifest, requests);
        collectGeneratedTools(manifest, requests);
        return List.copyOf(requests.values());
    }

    private void collectAliases(AuthoredManifest manifest, Map<String, SurfaceRequest> requests) {
        Map<LocalId, VersionAliasValue> aliases = manifest.versions()
                .map(AuthoredVersionAliases::entries)
                .orElseGet(Map::of);
        aliases.forEach((alias, value) -> {
            if (isSnapshot(value.value())) {
                return;
            }
            List<AliasReference> references = AliasReferences.referencing(manifest, alias.value());
            List<DiscoveryCoordinate> coordinates = references.stream()
                    .map(AliasReference::coordinate)
                    .flatMap(Optional::stream)
                    .map(DiscoveryCoordinate::of)
                    .flatMap(Optional::stream)
                    .distinct()
                    .toList();
            add(requests, new SurfaceRequest(
                    OutdatedSurface.VERSION_ALIAS,
                    alias.value(),
                    ManifestSections.VERSIONS,
                    value.value(),
                    coordinates,
                    true,
                    references.stream().map(AliasReference::label).toList()));
        });
    }

    /**
     * Lane order is the canonical dependency-table order the authored model already guarantees;
     * within a lane, entries are reported by normalized coordinate so a report never depends on the
     * order a human happened to type (design §5.6).
     */
    private void collectDependencies(AuthoredManifest manifest, Map<String, SurfaceRequest> requests) {
        List<AuthoredDependency> declarations = new ArrayList<>(manifest.dependencies()
                .map(AuthoredDependencies::declarations)
                .orElseGet(List::of));
        declarations.sort(Comparator
                .comparingInt((AuthoredDependency dependency) -> dependency.lane().canonicalOrder())
                .thenComparing(AuthoredDependency::coordinate));
        for (AuthoredDependency dependency : declarations) {
            if (!(dependency.selector() instanceof DependencySelector.FixedVersion fixed)
                    || isSnapshot(fixed.value())) {
                continue;
            }
            add(
                    requests,
                    dependency.coordinate(),
                    surfaceOf(dependency.lane()),
                    ManifestSections.lane(dependency.lane()),
                    fixed.value());
        }
    }

    private void collectPlatforms(AuthoredManifest manifest, Map<String, SurfaceRequest> requests) {
        manifest.platforms()
                .map(AuthoredPlatforms::entries)
                .orElseGet(Map::of)
                .forEach((coordinate, selector) -> addPlatform(
                        requests, coordinate, selector, OutdatedSurface.PLATFORM, ManifestSections.PLATFORMS));
    }

    private void collectConstraints(AuthoredManifest manifest, Map<String, SurfaceRequest> requests) {
        Map<DependencyCoordinate, AuthoredDependencyConstraint> constraints = manifest.dependencyConstraints()
                .map(AuthoredDependencyConstraints::entries)
                .orElseGet(Map::of);
        constraints.forEach((coordinate, constraint) -> {
            if (!(constraint.selector() instanceof DependencyConstraintSelector.FixedVersion fixed)
                    || isSnapshot(fixed.value())) {
                return;
            }
            add(
                    requests,
                    coordinate,
                    OutdatedSurface.DEPENDENCY_CONSTRAINT,
                    ManifestSections.DEPENDENCY_CONSTRAINTS,
                    fixed.value());
        });
    }

    private void collectBom(AuthoredManifest manifest, Map<String, SurfaceRequest> requests) {
        Optional<AuthoredBom> bom = manifest.packaging().bom();
        if (bom.isEmpty()) {
            return;
        }
        bom.orElseThrow().versions().orElseGet(Map::of).forEach((coordinate, version) -> addPlatform(
                requests,
                coordinate,
                version.selector(),
                OutdatedSurface.BOM_VERSION,
                ManifestSections.BOM_VERSIONS));
        bom.orElseThrow().imports().orElseGet(Map::of).forEach((coordinate, selector) -> addPlatform(
                requests, coordinate, selector, OutdatedSurface.BOM_IMPORT, ManifestSections.BOM_IMPORTS));
    }

    private void collectGeneratedTools(AuthoredManifest manifest, Map<String, SurfaceRequest> requests) {
        manifest.generated().ifPresent(generated -> generated.tools().declarations()
                .forEach((id, tool) -> collectTool(requests, id, tool)));
    }

    private void collectTool(
            Map<String, SurfaceRequest> requests,
            LocalId id,
            AuthoredGeneratedTool tool) {
        String section = ManifestSections.generatedTool(id);
        switch (tool) {
            case AuthoredGeneratedTool.OpenApi openApi -> addTool(
                    requests,
                    OutdatedSurface.OPENAPI_TOOL,
                    section,
                    openApi.coordinate(),
                    openApi.version());
            case AuthoredGeneratedTool.Protobuf protobuf -> {
                addTool(
                        requests,
                        OutdatedSurface.PROTOBUF_TOOL,
                        section,
                        protobuf.protocCoordinate(),
                        protobuf.protocVersion());
                addTool(
                        requests,
                        OutdatedSurface.PROTOBUF_TOOL,
                        section,
                        protobuf.grpcCoordinate(),
                        protobuf.grpcVersion());
            }
            case AuthoredGeneratedTool.Jvm jvm -> {
                for (GeneratedArtifactRequest request : jvm.coordinates()) {
                    addTool(
                            requests,
                            OutdatedSurface.EXEC_TOOL_COORDINATE,
                            section,
                            Optional.of(request.coordinate()),
                            Optional.of(request.selector()));
                }
            }
            case AuthoredGeneratedTool.Process ignored -> {
                // A process tool runs a PATH binary and declares no artifact coordinate to advance.
            }
        }
    }

    private void addTool(
            Map<String, SurfaceRequest> requests,
            OutdatedSurface surface,
            String section,
            Optional<DependencyCoordinate> coordinate,
            Optional<DependencySelector> selector) {
        if (coordinate.isEmpty() || selector.isEmpty()) {
            return;
        }
        if (!(selector.orElseThrow() instanceof DependencySelector.FixedVersion fixed)
                || isSnapshot(fixed.value())) {
            return;
        }
        add(requests, coordinate.orElseThrow(), surface, section, fixed.value());
    }

    private void addPlatform(
            Map<String, SurfaceRequest> requests,
            DependencyCoordinate coordinate,
            PlatformSelector selector,
            OutdatedSurface surface,
            String section) {
        if (!(selector instanceof PlatformSelector.FixedVersion fixed) || isSnapshot(fixed.value())) {
            return;
        }
        add(requests, coordinate, surface, section, fixed.value());
    }

    private void add(
            Map<String, SurfaceRequest> requests,
            DependencyCoordinate coordinate,
            OutdatedSurface surface,
            String section,
            String version) {
        DiscoveryCoordinate.of(coordinate.value()).ifPresent(discovery -> add(
                requests,
                new SurfaceRequest(
                        surface, coordinate.value(), section, version, List.of(discovery), false, List.of())));
    }

    private static OutdatedSurface surfaceOf(DependencyLane lane) {
        return switch (lane) {
            case PROCESSOR, TEST_PROCESSOR -> OutdatedSurface.ANNOTATION_PROCESSOR;
            case API, IMPLEMENTATION, RUNTIME, PROVIDED, DEV, TEST -> OutdatedSurface.DEPENDENCY;
        };
    }

    private static boolean isSnapshot(String version) {
        return VersionStability.of(version) == VersionStability.SNAPSHOT;
    }

    private static void add(Map<String, SurfaceRequest> requests, SurfaceRequest request) {
        requests.putIfAbsent(request.surface() + "|" + request.identifier() + "|" + request.section(), request);
    }
}
