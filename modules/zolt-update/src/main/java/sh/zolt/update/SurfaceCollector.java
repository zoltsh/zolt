package sh.zolt.update;

import sh.zolt.dependency.VersionStability;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ExecToolCoordinate;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.WorkspaceConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Walks a {@link ProjectConfig} and enumerates every discoverable version surface: version aliases,
 * literal-versioned dependencies in every scope, annotation processors, platforms, dependency
 * constraints, and generated exec/protobuf/openapi tool coordinates. versionRef-backed entries are
 * skipped (they report under their alias); SNAPSHOT literals and workspace-member dependencies are
 * ignored.
 */
final class SurfaceCollector {
    private static final String PROJECT_TOOL = "project";

    List<SurfaceRequest> collect(ProjectConfig config) {
        Map<String, SurfaceRequest> requests = new LinkedHashMap<>();
        collectAliases(config, requests);
        collectDependencyMap(config, config.dependencies(), "dependencies", OutdatedSurface.DEPENDENCY, requests);
        collectDependencyMap(config, config.apiDependencies(), "api.dependencies", OutdatedSurface.DEPENDENCY, requests);
        collectDependencyMap(
                config, config.runtimeDependencies(), "runtime.dependencies", OutdatedSurface.DEPENDENCY, requests);
        collectDependencyMap(
                config, config.providedDependencies(), "provided.dependencies", OutdatedSurface.DEPENDENCY, requests);
        collectDependencyMap(config, config.devDependencies(), "dev.dependencies", OutdatedSurface.DEPENDENCY, requests);
        collectDependencyMap(config, config.testDependencies(), "test.dependencies", OutdatedSurface.DEPENDENCY, requests);
        collectDependencyMap(
                config,
                config.annotationProcessors(),
                "annotationProcessors",
                OutdatedSurface.ANNOTATION_PROCESSOR,
                requests);
        collectDependencyMap(
                config,
                config.testAnnotationProcessors(),
                "test.annotationProcessors",
                OutdatedSurface.ANNOTATION_PROCESSOR,
                requests);
        collectDependencyMap(config, config.platforms(), "platforms", OutdatedSurface.PLATFORM, requests);
        collectConstraints(config, requests);
        collectGenerated(config, requests);
        return List.copyOf(requests.values());
    }

    List<SurfaceRequest> collect(WorkspaceConfig config) {
        Map<String, SurfaceRequest> requests = new LinkedHashMap<>();
        config.platforms().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (isSnapshot(entry.getValue())) {
                        return;
                    }
                    DiscoveryCoordinate.of(entry.getKey()).ifPresent(discovery -> add(
                            requests,
                            new SurfaceRequest(
                                    OutdatedSurface.PLATFORM,
                                    entry.getKey(),
                                    "[platforms]",
                                    entry.getValue(),
                                    List.of(discovery),
                                    false,
                                    List.of())));
                });
        return List.copyOf(requests.values());
    }

    private void collectAliases(ProjectConfig config, Map<String, SurfaceRequest> requests) {
        config.versionAliases().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (isSnapshot(entry.getValue())) {
                        return;
                    }
                    List<AliasReference> references = AliasReferences.referencing(config, entry.getKey());
                    List<DiscoveryCoordinate> coordinates = references.stream()
                            .map(AliasReference::coordinate)
                            .flatMap(Optional::stream)
                            .map(DiscoveryCoordinate::of)
                            .flatMap(Optional::stream)
                            .distinct()
                            .toList();
                    List<String> governs = references.stream().map(AliasReference::label).toList();
                    add(requests, new SurfaceRequest(
                            OutdatedSurface.VERSION_ALIAS,
                            entry.getKey(),
                            "[versions]",
                            entry.getValue(),
                            coordinates,
                            true,
                            governs));
                });
    }

    private void collectDependencyMap(
            ProjectConfig config,
            Map<String, String> dependencies,
            String section,
            OutdatedSurface surface,
            Map<String, SurfaceRequest> requests) {
        dependencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String coordinate = entry.getKey();
                    String version = entry.getValue();
                    if (isVersionRef(config, section, coordinate) || isSnapshot(version)) {
                        return;
                    }
                    DiscoveryCoordinate.of(coordinate).ifPresent(discovery -> add(requests, new SurfaceRequest(
                            surface, coordinate, "[" + section + "]", version, List.of(discovery), false, List.of())));
                });
    }

    private void collectConstraints(ProjectConfig config, Map<String, SurfaceRequest> requests) {
        config.dependencyPolicy().constraints().values().stream()
                .sorted(Comparator.comparing(DependencyConstraint::coordinate))
                .forEach(constraint -> {
                    if (constraint.versionRef().isPresent()) {
                        return;
                    }
                    String version = constraint.version();
                    if (version == null || version.isBlank() || isSnapshot(version)) {
                        return;
                    }
                    DiscoveryCoordinate.of(constraint.coordinate()).ifPresent(discovery -> add(requests, new SurfaceRequest(
                            OutdatedSurface.DEPENDENCY_CONSTRAINT,
                            constraint.coordinate(),
                            "[dependencyConstraints]",
                            version,
                            List.of(discovery),
                            false,
                            List.of())));
                });
    }

    private void collectGenerated(ProjectConfig config, Map<String, SurfaceRequest> requests) {
        List<GeneratedSourceStep> steps = new ArrayList<>(config.build().generatedMainSources());
        steps.addAll(config.build().generatedTestSources());
        for (GeneratedSourceStep step : steps) {
            collectExec(step, requests);
            collectProtobuf(step, requests);
            collectOpenApi(step, requests);
        }
    }

    private void collectExec(GeneratedSourceStep step, Map<String, SurfaceRequest> requests) {
        if (step.kind() != GeneratedSourceKind.EXEC || PROJECT_TOOL.equals(step.exec().toolName())) {
            return;
        }
        for (ExecToolCoordinate coordinate : step.exec().tool().coordinates()) {
            if (coordinate.versionRef().isPresent() || coordinate.version().isEmpty()) {
                continue;
            }
            String version = coordinate.version().orElseThrow();
            if (isSnapshot(version)) {
                continue;
            }
            DiscoveryCoordinate.of(coordinate.coordinate()).ifPresent(discovery -> add(requests, new SurfaceRequest(
                    OutdatedSurface.EXEC_TOOL_COORDINATE,
                    coordinate.coordinate(),
                    "[generated.execTools." + step.exec().toolName() + "]",
                    version,
                    List.of(discovery),
                    false,
                    List.of())));
        }
    }

    private void collectProtobuf(GeneratedSourceStep step, Map<String, SurfaceRequest> requests) {
        if (step.kind() != GeneratedSourceKind.PROTOBUF) {
            return;
        }
        addToolRef(
                requests,
                OutdatedSurface.PROTOBUF_TOOL,
                step.protobuf().protocCoordinate(),
                step.protobuf().protocVersion(),
                step.protobuf().protocVersionRef(),
                "[generated.protobufTool]");
        addToolRef(
                requests,
                OutdatedSurface.PROTOBUF_TOOL,
                step.protobuf().grpcPluginCoordinate(),
                step.protobuf().grpcPluginVersion(),
                step.protobuf().grpcPluginVersionRef(),
                "[generated.protobufTool]");
    }

    private void collectOpenApi(GeneratedSourceStep step, Map<String, SurfaceRequest> requests) {
        if (step.kind() != GeneratedSourceKind.OPENAPI) {
            return;
        }
        addToolRef(
                requests,
                OutdatedSurface.OPENAPI_TOOL,
                step.openApi().toolCoordinate(),
                step.openApi().toolVersion(),
                step.openApi().toolVersionRef(),
                "[generated.openapiTool]");
    }

    private void addToolRef(
            Map<String, SurfaceRequest> requests,
            OutdatedSurface surface,
            Optional<String> coordinate,
            Optional<String> version,
            Optional<String> versionRef,
            String section) {
        if (versionRef.isPresent() || coordinate.isEmpty() || version.isEmpty() || isSnapshot(version.orElseThrow())) {
            return;
        }
        DiscoveryCoordinate.of(coordinate.orElseThrow()).ifPresent(discovery -> add(requests, new SurfaceRequest(
                surface, coordinate.orElseThrow(), section, version.orElseThrow(), List.of(discovery), false, List.of())));
    }

    private static boolean isVersionRef(ProjectConfig config, String section, String coordinate) {
        DependencyMetadata metadata = config.dependencyMetadata().get(DependencyMetadata.key(section, coordinate));
        return metadata != null && metadata.versionRef() != null;
    }

    private static boolean isSnapshot(String version) {
        return VersionStability.of(version) == VersionStability.SNAPSHOT;
    }

    private static void add(Map<String, SurfaceRequest> requests, SurfaceRequest request) {
        requests.putIfAbsent(request.surface() + "|" + request.identifier() + "|" + request.section(), request);
    }
}
