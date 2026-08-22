package sh.zolt.tree;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockConflict;
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

abstract class DependencyWhyTestSupport extends DependencyTreeTestSupport {
    protected static ZoltLockfile lockfile() {
        return lockfile(
                List.of(
                        lockPackage("com.example", "app", "1.0.0", true, List.of("com.example:lib:1.0.0")),
                        lockPackage("com.example", "lib", "1.0.0", false, List.of())),
                List.of());
    }

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

    protected static ZoltLockfile excludedLockfile() {
        return lockfile(
                List.of(lockPackage("com.example", "app", "1.0.0", true, List.of())),
                List.of(),
                List.of(new LockPolicyEffect(
                        "global-exclusion",
                        new PackageId("commons-logging", "commons-logging"),
                        Optional.of("1.2"),
                        Optional.of("com.example:app:1.0.0"),
                        "[dependencies.policy].deny commons-logging:commons-logging (Use jcl-over-slf4j)")));
    }

    protected static ZoltLockfile conflictedLockfile() {
        return lockfile(
                List.of(
                        lockPackage("com.example", "app", "1.0.0", true, List.of("com.example:lib:2.0.0")),
                        lockPackage("com.example", "lib", "2.0.0", false, List.of())),
                List.of(new LockConflict(
                        new PackageId("com.example", "lib"),
                        "2.0.0",
                        List.of("1.0.0", "2.0.0"),
                        ConflictSelectionReason.NEWEST_VERSION)));
    }

    protected static ZoltLockfile strictPolicyLockfile() {
        return lockfile(
                List.of(
                        lockPackage("com.example", "app", "1.0.0", true, List.of("com.example:lib:2.0.0")),
                        lockPackage(
                                "com.example",
                                "lib",
                                "2.0.0",
                                false,
                                List.of(),
                                List.of("strict-version: com.example:lib requested 1.0.0 -> 2.0.0 (baseline)"))),
                List.of(),
                List.of(new LockPolicyEffect(
                        "strict-version",
                        new PackageId("com.example", "lib"),
                        Optional.of("1.0.0"),
                        Optional.of("com.example:app:1.0.0"),
                        "strict-version: com.example:lib requested 1.0.0 -> 2.0.0 (baseline)")));
    }
}
