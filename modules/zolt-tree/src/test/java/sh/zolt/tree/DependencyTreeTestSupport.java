package sh.zolt.tree;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class DependencyTreeTestSupport {
    protected static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    protected static LockPackage lockPackage(
            String groupId,
            String artifactId,
            String version,
            boolean direct,
            List<String> dependencies) {
        return new LockPackage(
                new PackageId(groupId, artifactId),
                version,
                "maven-central",
                DependencyScope.COMPILE,
                direct,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies);
    }

    protected static LockPackage lockPackage(
            String groupId,
            String artifactId,
            String version,
            boolean direct,
            List<String> dependencies,
            List<String> policies) {
        return new LockPackage(
                new PackageId(groupId, artifactId),
                version,
                "maven-central",
                DependencyScope.COMPILE,
                direct,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                policies);
    }

    /** A classified-jar lock entry whose variant identity is recovered from its jar filename. */
    protected static LockPackage classified(
            String groupId,
            String artifactId,
            String version,
            String classifier) {
        String base = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version;
        return new LockPackage(
                new PackageId(groupId, artifactId),
                version,
                "maven-central",
                DependencyScope.COMPILE,
                false,
                Optional.of(base + "-" + classifier + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of("jar-" + classifier),
                Optional.of("pom-sha"),
                List.of());
    }

    protected static LockPolicyEffect policyEffect() {
        return new LockPolicyEffect(
                "global-exclusion",
                new PackageId("commons-logging", "commons-logging"),
                Optional.of("1.2"),
                Optional.of("com.example:app:1.0.0"),
                "[dependencies.policy].deny commons-logging:commons-logging (Use jcl-over-slf4j)");
    }

    protected static ZoltLockfile lockfile(
            List<LockPackage> packages,
            List<LockConflict> conflicts) {
        return lockfile(packages, conflicts, List.of());
    }

    protected static ZoltLockfile lockfile(
            List<LockPackage> packages,
            List<LockConflict> conflicts,
            List<LockPolicyEffect> effects) {
        List<LockDependencyRoot> roots = packages.stream()
                .filter(LockPackage::direct)
                .map(lockPackage -> root(".", lockPackage, DependencyLane.IMPLEMENTATION))
                .toList();
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                packages,
                conflicts,
                effects,
                List.of(),
                roots);
    }

    protected static LockDependencyRoot root(
            String member,
            LockPackage lockPackage,
            DependencyLane lane) {
        return new LockDependencyRoot(
                member,
                lockPackage.packageId(),
                lockPackage.version(),
                LockArtifactVariant.of(lockPackage),
                lane,
                Optional.of(lockPackage.scope()),
                false,
                false);
    }
}
