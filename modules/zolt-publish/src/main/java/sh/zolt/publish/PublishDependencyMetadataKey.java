package sh.zolt.publish;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;

/**
 * Locates declaration metadata for a direct published dependency. Compile-scope API declarations
 * retain their {@code api.dependencies} origin instead of being mistaken for implementation
 * dependencies, so classifier/type/optional/exclusion metadata survives POM projection.
 */
public final class PublishDependencyMetadataKey {
    private PublishDependencyMetadataKey() {
    }

    public static String of(ProjectConfig config, DependencyScope scope, String coordinate) {
        return switch (scope) {
            case COMPILE -> DependencyMetadata.key(
                    apiDependency(config, coordinate) ? "api.dependencies" : "dependencies",
                    coordinate);
            case RUNTIME -> DependencyMetadata.key("runtime.dependencies", coordinate);
            case PROVIDED -> DependencyMetadata.key("provided.dependencies", coordinate);
            default -> DependencyMetadata.key("dependencies", coordinate);
        };
    }

    private static boolean apiDependency(ProjectConfig config, String coordinate) {
        return config.apiDependencies().containsKey(coordinate)
                || config.managedApiDependencies().contains(coordinate)
                || config.workspaceApiDependencies().containsKey(coordinate);
    }
}
