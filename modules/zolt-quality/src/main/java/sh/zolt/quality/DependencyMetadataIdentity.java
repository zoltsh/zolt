package sh.zolt.quality;

import java.util.Optional;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyEdge;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyMetadata;

/** Exact lock identity and edge helpers for dependency-metadata checks. */
final class DependencyMetadataIdentity {
    private DependencyMetadataIdentity() {
    }

    static Optional<LockPackage> find(
            ZoltLockfile lockfile,
            DependencyMetadata metadata) {
        PackageId packageId = packageId(metadata.coordinate());
        LockArtifactVariant variant = declaredVariant(metadata);
        DependencyScope scope = scope(metadata.section());
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .filter(lockPackage -> LockArtifactVariant.of(lockPackage).equals(variant))
                .filter(lockPackage -> lockPackage.scope() == scope)
                .findFirst();
    }

    static boolean containsDependency(
            LockPackage lockPackage,
            PackageId excluded) {
        return lockPackage.dependencies().stream()
                .map(DependencyMetadataIdentity::edgePackageId)
                .flatMap(Optional::stream)
                .anyMatch(excluded::equals);
    }

    static boolean recordsAppliedEdgeExclusion(
            ZoltLockfile lockfile,
            LockPackage source,
            PackageId excluded) {
        String sourceCoordinate = source.packageId() + ":" + source.version();
        return lockfile.policyEffects().stream()
                .filter(effect -> "edge-exclusion".equals(effect.kind()))
                .filter(effect -> effect.packageId().equals(excluded))
                .anyMatch(effect -> effect.source()
                        .map(sourceCoordinate::equals)
                        .orElse(false));
    }

    static LockArtifactVariant declaredVariant(DependencyMetadata metadata) {
        return new LockArtifactVariant(
                metadata.type() == null ? "jar" : metadata.type(),
                Optional.ofNullable(metadata.classifier()));
    }

    static DependencyScope scope(String section) {
        return switch (section) {
            case "api.dependencies", "dependencies" -> DependencyScope.COMPILE;
            case "runtime.dependencies" -> DependencyScope.RUNTIME;
            case "provided.dependencies" -> DependencyScope.PROVIDED;
            case "dev.dependencies" -> DependencyScope.DEV;
            case "test.dependencies" -> DependencyScope.TEST;
            case "annotationProcessors" -> DependencyScope.PROCESSOR;
            case "test.annotationProcessors" -> DependencyScope.TEST_PROCESSOR;
            default -> throw new IllegalArgumentException(
                    "Unsupported dependency metadata section `" + section + "`.");
        };
    }

    static String workspaceScope(DependencyScope scope) {
        return switch (scope) {
            case COMPILE -> "compile";
            case TEST -> "test";
            case PROCESSOR -> "processor";
            case TEST_PROCESSOR -> "test-processor";
            default -> throw new IllegalArgumentException(
                    "Dependency scope `" + scope.lockfileName() + "` does not support workspace members.");
        };
    }

    static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }

    private static Optional<PackageId> edgePackageId(String encoded) {
        Optional<LockDependencyEdge> parsed = LockDependencyEdge.parse(encoded);
        if (parsed.isPresent()) {
            return parsed.map(LockDependencyEdge::packageId);
        }
        String[] legacy = encoded.split(":", -1);
        if (legacy.length == 2
                && !legacy[0].isBlank()
                && !legacy[1].isBlank()) {
            return Optional.of(new PackageId(legacy[0], legacy[1]));
        }
        return Optional.empty();
    }
}
