package sh.zolt.quality;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;

/** Collects explicit metadata and normalized workspace declarations for member quality auditing. */
final class WorkspaceDependencyMetadataDeclarations {
    private WorkspaceDependencyMetadataDeclarations() {
    }

    static SortedMap<String, DependencyMetadata> all(ProjectConfig config) {
        SortedMap<String, DependencyMetadata> declarations =
                new TreeMap<>(config.dependencyMetadata());
        add(declarations, "api.dependencies", config.workspaceApiDependencies());
        add(declarations, "dependencies", config.workspaceDependencies());
        add(declarations, "test.dependencies", config.workspaceTestDependencies());
        add(declarations, "annotationProcessors", config.workspaceAnnotationProcessors());
        add(declarations, "test.annotationProcessors", config.workspaceTestAnnotationProcessors());
        return declarations;
    }

    private static void add(
            Map<String, DependencyMetadata> declarations,
            String section,
            Map<String, String> workspaceDependencies) {
        workspaceDependencies.forEach((coordinate, workspace) ->
                declarations.putIfAbsent(
                        DependencyMetadata.key(section, coordinate),
                        new DependencyMetadata(
                                section,
                                coordinate,
                                null,
                                null,
                                false,
                                workspace,
                                false,
                                false,
                                List.of(),
                                null,
                                null)));
    }
}
