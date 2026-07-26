package sh.zolt.policy;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;

/** Variant- and scope-qualified direct-version diagnostics for dependency policy reports. */
final class DirectDependencyPolicyDiagnostics {
    private DirectDependencyPolicyDiagnostics() {
    }

    static List<DependencyPolicyReport.DirectVersionDiagnostic> directVersions(
            ProjectConfig config,
            ZoltLockfile lockfile) {
        return explicitDirectVersions(config).values().stream()
                .sorted(Comparator.comparing(direct -> direct.section() + ":" + direct.coordinate()))
                .map(direct -> new DependencyPolicyReport.DirectVersionDiagnostic(
                        direct.section(),
                        direct.coordinate(),
                        direct.version(),
                        direct.versionRef(),
                        directVersionStatus(direct, lockfile)))
                .toList();
    }

    static boolean hasExplicitDirectVersion(
            ProjectConfig config,
            String coordinate) {
        return explicitDirectVersions(config).values().stream()
                .anyMatch(direct -> direct.coordinate().equals(coordinate));
    }

    private static String directVersionStatus(
            DirectDependency direct,
            ZoltLockfile lockfile) {
        PackageId packageId = packageId(direct.coordinate());
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .filter(LockPackage::direct)
                .filter(lockPackage -> lockPackage.scope() == direct.scope())
                .filter(lockPackage -> LockArtifactVariant.of(lockPackage).equals(direct.variant()))
                .anyMatch(lockPackage -> lockPackage.version().equals(direct.version()))
                ? "selected"
                : "not-selected";
    }

    private static Map<String, DirectDependency> explicitDirectVersions(
            ProjectConfig config) {
        Map<String, DirectDependency> directDependencies = new LinkedHashMap<>();
        addDirectVersions(
                directDependencies,
                "api.dependencies",
                config.apiDependencies(),
                config.dependencyMetadata(),
                DependencyScope.COMPILE);
        addDirectVersions(
                directDependencies,
                "dependencies",
                config.dependencies(),
                config.dependencyMetadata(),
                DependencyScope.COMPILE);
        addDirectVersions(
                directDependencies,
                "runtime.dependencies",
                config.runtimeDependencies(),
                config.dependencyMetadata(),
                DependencyScope.RUNTIME);
        addDirectVersions(
                directDependencies,
                "provided.dependencies",
                config.providedDependencies(),
                config.dependencyMetadata(),
                DependencyScope.PROVIDED);
        addDirectVersions(
                directDependencies,
                "dev.dependencies",
                config.devDependencies(),
                config.dependencyMetadata(),
                DependencyScope.DEV);
        addDirectVersions(
                directDependencies,
                "test.dependencies",
                config.testDependencies(),
                config.dependencyMetadata(),
                DependencyScope.TEST);
        addDirectVersions(
                directDependencies,
                "annotationProcessors",
                config.annotationProcessors(),
                config.dependencyMetadata(),
                DependencyScope.PROCESSOR);
        addDirectVersions(
                directDependencies,
                "test.annotationProcessors",
                config.testAnnotationProcessors(),
                config.dependencyMetadata(),
                DependencyScope.TEST_PROCESSOR);
        return directDependencies;
    }

    private static void addDirectVersions(
            Map<String, DirectDependency> directDependencies,
            String section,
            Map<String, String> dependencies,
            Map<String, DependencyMetadata> dependencyMetadata,
            DependencyScope scope) {
        dependencies.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    DependencyMetadata metadata =
                            dependencyMetadata.get(DependencyMetadata.key(section, entry.getKey()));
                    LockArtifactVariant variant = metadata == null
                            ? LockArtifactVariant.defaultVariant()
                            : new LockArtifactVariant(
                                    metadata.type() == null ? "jar" : metadata.type(),
                                    Optional.ofNullable(metadata.classifier()));
                    directDependencies.put(
                            section + ":" + entry.getKey(),
                            new DirectDependency(
                                    section,
                                    entry.getKey(),
                                    entry.getValue(),
                                    metadata == null
                                            ? Optional.empty()
                                            : Optional.ofNullable(metadata.versionRef()),
                                    variant,
                                    scope));
                });
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }

    private record DirectDependency(
            String section,
            String coordinate,
            String version,
            Optional<String> versionRef,
            LockArtifactVariant variant,
            DependencyScope scope) {
    }
}
