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
import java.util.List;
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
        Set<WorkspaceOptionalPackage> optionalRoots = new LinkedHashSet<>();
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
                    .map(WorkspaceOptionalPackageClosure::identity)
                    .ifPresent(optionalRoots::add);
        }
        Set<WorkspaceOptionalPackage> requiredRoots = new LinkedHashSet<>();
        lockfile.packages().stream()
                .filter(LockPackage::direct)
                .map(WorkspaceOptionalPackageClosure::identity)
                .filter(identity -> !optionalRoots.contains(identity))
                .forEach(requiredRoots::add);

        Set<WorkspaceOptionalPackage> optional =
                closure(optionalRoots, lockfile.packages(), index);
        optional.removeAll(closure(requiredRoots, lockfile.packages(), index));
        return Set.copyOf(optional);
    }

    private static Set<WorkspaceOptionalPackage> closure(
            Set<WorkspaceOptionalPackage> roots,
            List<LockPackage> packages,
            LockDependencyIndex index) {
        ArrayDeque<LockPackage> queue = new ArrayDeque<>();
        for (WorkspaceOptionalPackage root : roots) {
            packages.stream()
                    .filter(lockPackage -> identity(lockPackage).equals(root))
                    .findFirst()
                    .ifPresent(queue::addLast);
        }
        Set<WorkspaceOptionalPackage> reached = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            LockPackage current = queue.removeFirst();
            WorkspaceOptionalPackage identity = identity(current);
            if (!reached.add(identity)) {
                continue;
            }
            for (String dependency : current.dependencies()) {
                index.resolveGraphEdge(dependency, "zolt resolve --workspace")
                        .filter(candidate -> !reached.contains(identity(candidate)))
                        .ifPresent(queue::addLast);
            }
        }
        return reached;
    }

    private static WorkspaceOptionalPackage identity(LockPackage lockPackage) {
        return new WorkspaceOptionalPackage(
                lockPackage.packageId(),
                LockArtifactVariant.of(lockPackage),
                lockPackage.scope());
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
