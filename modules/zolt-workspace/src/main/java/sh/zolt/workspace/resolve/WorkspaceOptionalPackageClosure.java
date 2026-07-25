package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** Computes the full package closure hidden behind each optional external direct declaration. */
final class WorkspaceOptionalPackageClosure {
    private WorkspaceOptionalPackageClosure() {
    }

    static Set<WorkspaceOptionalPackage> from(
            ProjectConfig config,
            ZoltLockfile lockfile) {
        LockDependencyIndex index = new LockDependencyIndex(lockfile.packages());
        ArrayDeque<LockPackage> queue = new ArrayDeque<>();
        for (DependencyMetadata metadata : config.dependencyMetadata().values()) {
            if (!metadata.optional() || metadata.workspace() != null) {
                continue;
            }
            DependencyScope scope = scope(metadata.section());
            LockArtifactVariant variant = new LockArtifactVariant(
                    metadata.type() == null ? "jar" : metadata.type(),
                    Optional.ofNullable(metadata.classifier()));
            PackageId packageId = packageId(metadata.coordinate());
            lockfile.packages().stream()
                    .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                    .filter(lockPackage -> LockArtifactVariant.of(lockPackage).equals(variant))
                    .filter(lockPackage -> lockPackage.scope() == scope)
                    .findFirst()
                    .ifPresent(queue::addLast);
        }
        Set<WorkspaceOptionalPackage> optional = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            LockPackage current = queue.removeFirst();
            WorkspaceOptionalPackage identity = new WorkspaceOptionalPackage(
                    current.packageId(),
                    LockArtifactVariant.of(current),
                    current.scope());
            if (!optional.add(identity)) {
                continue;
            }
            for (String dependency : current.dependencies()) {
                index.resolveGraphEdge(dependency, "zolt resolve --workspace")
                        .filter(candidate -> !optional.contains(new WorkspaceOptionalPackage(
                                candidate.packageId(),
                                LockArtifactVariant.of(candidate),
                                candidate.scope())))
                        .ifPresent(queue::addLast);
            }
        }
        return Set.copyOf(optional);
    }

    private static DependencyScope scope(String section) {
        return switch (section) {
            case "api.dependencies", "dependencies" -> DependencyScope.COMPILE;
            case "runtime.dependencies" -> DependencyScope.RUNTIME;
            case "provided.dependencies" -> DependencyScope.PROVIDED;
            case "dev.dependencies" -> DependencyScope.DEV;
            case "test.dependencies" -> DependencyScope.TEST;
            case "annotationProcessors" -> DependencyScope.PROCESSOR;
            case "test.annotationProcessors" -> DependencyScope.TEST_PROCESSOR;
            default -> throw new IllegalArgumentException(
                    "Unsupported optional dependency section `" + section + "`.");
        };
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }
}
