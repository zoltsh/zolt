package sh.zolt.workspace.resolve;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveOutput;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
                output.dependencyReachability().stream()
                        .filter(sh.zolt.resolve.ResolvedDependencyReachability::optionalOnly)
                        .map(fact -> new WorkspaceOptionalPackage(
                                fact.packageId(),
                                fact.variant(),
                                fact.scope()))
                        .collect(Collectors.toUnmodifiableSet()));
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
                        java.util.Optional.ofNullable(classifier)));
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }
}
