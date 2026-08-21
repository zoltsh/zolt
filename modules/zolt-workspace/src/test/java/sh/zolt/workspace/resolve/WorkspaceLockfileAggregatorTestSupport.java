package sh.zolt.workspace.resolve;

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
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;

/** Shared workspace/lock fixtures for the aggregator tests (base graph, external and classified entries). */
abstract class WorkspaceLockfileAggregatorTestSupport {
    @TempDir
    protected Path tempDir;

    protected Workspace workspace(List<WorkspaceProjectEdge> edges) throws IOException {
        Files.createDirectories(tempDir.resolve("apps/api"));
        Files.createDirectories(tempDir.resolve("apps/worker"));
        Files.createDirectories(tempDir.resolve("modules/core"));
        Files.createDirectories(tempDir.resolve("modules/processor"));
        List<WorkspaceMember> members = List.of(
                new WorkspaceMember("apps/api", tempDir.resolve("apps/api"), config("api")),
                new WorkspaceMember("apps/worker", tempDir.resolve("apps/worker"), config("worker")),
                new WorkspaceMember("modules/core", tempDir.resolve("modules/core"), config("core")),
                new WorkspaceMember("modules/processor", tempDir.resolve("modules/processor"), config("processor")));
        return new Workspace(
                tempDir,
                tempDir.resolve("zolt-workspace.toml"),
                new WorkspaceConfig(
                        "acme-platform",
                        members.stream().map(WorkspaceMember::path).toList(),
                        List.of(),
                        Map.of(),
                        Map.of()),
                members,
                edges,
                List.of("modules/core", "modules/processor", "apps/api", "apps/worker"));
    }

    protected static ZoltLockfile lockfile(
            List<LockPackage> packages,
            List<LockConflict> conflicts,
            List<LockPolicyEffect> policyEffects) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.of("sha256:project"),
                List.of("repositories=sha256:repo"),
                packages,
                conflicts,
                policyEffects);
    }

    protected static LockPackage externalPackage(
            PackageId packageId,
            String version,
            boolean direct,
            List<String> dependencies,
            List<String> policies) {
        return externalPackage(packageId, version, direct, dependencies, policies, List.of());
    }

    protected static LockPackage externalPackage(
            PackageId packageId,
            String version,
            boolean direct,
            List<String> dependencies,
            List<String> policies,
            List<String> members) {
        return new LockPackage(
                packageId,
                version,
                "central",
                DependencyScope.COMPILE,
                direct,
                Optional.of(packageId.groupId() + "/" + packageId.artifactId() + "/" + version + ".jar"),
                Optional.of(packageId.groupId() + "/" + packageId.artifactId() + "/" + version + ".pom"),
                Optional.of("jar-" + version),
                Optional.of("pom-" + version),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                dependencies,
                members,
                List.of(),
                policies,
                List.of());
    }

    protected static LockPackage classifiedExternalPackage(PackageId packageId, String version, String classifier) {
        String base = packageId.groupId().replace('.', '/')
                + "/"
                + packageId.artifactId()
                + "/"
                + version
                + "/"
                + packageId.artifactId()
                + "-"
                + version;
        return new LockPackage(
                packageId,
                version,
                "central",
                DependencyScope.COMPILE,
                false,
                Optional.of(base + "-" + classifier + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of("jar-" + classifier),
                Optional.of("pom-sha"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    protected static LockPackage packageById(ZoltLockfile lockfile, String group, String artifact) {
        PackageId packageId = new PackageId(group, artifact);
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .findFirst()
                .orElseThrow();
    }

    protected static String packageSummary(LockPackage lockPackage) {
        return lockPackage.packageId()
                + ":"
                + lockPackage.source()
                + ":"
                + lockPackage.scope().lockfileName();
    }

    protected static ProjectConfig config(String name) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "0.1.0", "com.acme", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }
}
