package sh.zolt.workspace.resolve;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolvedDependencyReachability;
import sh.zolt.resolve.ResolveOutput;

final class WorkspaceMemberResolveOutputFacts {
    private WorkspaceMemberResolveOutputFacts() {
    }

    static WorkspaceMemberResolveOutput of(
            String member,
            ProjectConfig config,
            ResolveOutput output) {
        ZoltLockfile lockfile = output.lockfile();
        return new WorkspaceMemberResolveOutput(
                member,
                lockfile,
                exportedExternalPackages(config),
                declaredOptionalPackages(config),
                output.dependencyReachability().stream()
                        .filter(ResolvedDependencyReachability::optionalOnly)
                        .map(fact -> new WorkspaceOptionalPackage(
                                fact.packageId(),
                                fact.variant(),
                                fact.scope()))
                        .collect(Collectors.toUnmodifiableSet()));
    }

    private static Set<WorkspaceOptionalPackage> declaredOptionalPackages(ProjectConfig config) {
        return config.dependencyMetadata().values().stream()
                .filter(DependencyMetadata::optional)
                .filter(metadata -> !metadata.publishOnly())
                .filter(metadata -> metadata.workspace() == null)
                .map(WorkspaceMemberResolveOutputFacts::declaredOptionalPackage)
                .flatMap(Optional::stream)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Optional<WorkspaceOptionalPackage> declaredOptionalPackage(
            DependencyMetadata metadata) {
        DependencyScope scope = switch (metadata.section()) {
            case "api.dependencies", "dependencies" -> DependencyScope.COMPILE;
            case "runtime.dependencies" -> DependencyScope.RUNTIME;
            case "provided.dependencies" -> DependencyScope.PROVIDED;
            case "dev.dependencies" -> DependencyScope.DEV;
            case "test.dependencies" -> DependencyScope.TEST;
            case "annotationProcessors" -> DependencyScope.PROCESSOR;
            case "test.annotationProcessors" -> DependencyScope.TEST_PROCESSOR;
            default -> null;
        };
        if (scope == null) {
            return Optional.empty();
        }
        return Optional.of(new WorkspaceOptionalPackage(
                packageId(metadata.coordinate()),
                new LockArtifactVariant(
                        metadata.type() == null ? "jar" : metadata.type(),
                        Optional.ofNullable(metadata.classifier())),
                scope));
    }

    private static Set<WorkspaceExportedPackage> exportedExternalPackages(
            ProjectConfig config) {
        Set<WorkspaceExportedPackage> packages = new LinkedHashSet<>();
        config.apiDependencies().keySet().stream()
                .filter(coordinate -> !optional(
                        config, coordinate))
                .forEach(coordinate ->
                        packages.add(exportedPackage(config, coordinate)));
        config.managedApiDependencies().stream()
                .filter(coordinate -> !optional(
                        config, coordinate))
                .forEach(coordinate ->
                        packages.add(exportedPackage(config, coordinate)));
        return Set.copyOf(packages);
    }

    private static boolean optional(
            ProjectConfig config,
            String coordinate) {
        DependencyMetadata metadata = config.dependencyMetadata()
                .get(DependencyMetadata.key(
                        "api.dependencies", coordinate));
        return metadata != null && metadata.optional();
    }

    private static WorkspaceExportedPackage exportedPackage(
            ProjectConfig config,
            String coordinate) {
        DependencyMetadata metadata = config.dependencyMetadata()
                .get(DependencyMetadata.key(
                        "api.dependencies", coordinate));
        String extension = metadata == null || metadata.type() == null
                ? "jar"
                : metadata.type();
        String classifier = metadata == null ? null : metadata.classifier();
        return new WorkspaceExportedPackage(
                packageId(coordinate),
                new LockArtifactVariant(
                        extension,
                        Optional.ofNullable(classifier)));
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }
}
