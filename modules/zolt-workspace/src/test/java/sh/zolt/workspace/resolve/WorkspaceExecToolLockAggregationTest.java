package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link WorkspaceLockfileAggregator} for {@code tool-exec} packages: the aggregated root lock
 * must keep each exec tool's own locked version of a shared library (never mediating conflicting versions
 * into one) while unioning tool groups for jars shared at the same version, and stay byte-deterministic.
 */
final class WorkspaceExecToolLockAggregationTest {
    @TempDir
    private Path tempDir;

    @Test
    void isolatesConflictingExecToolSharedLibraryVersionsUnderSeparateToolGroups() throws IOException {
        Workspace workspace = workspace();
        PackageId alpha = new PackageId("com.example", "alpha-codegen");
        PackageId beta = new PackageId("com.example", "beta-codegen");
        PackageId shared = new PackageId("com.example", "shared-lib");
        PackageId common = new PackageId("com.example", "common-lib");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(
                        new WorkspaceMemberResolveOutput(
                                "apps/api",
                                lockfile(List.of(
                                        execToolPackage(alpha, "1.0.0", true, List.of("alpha")),
                                        execToolPackage(shared, "1.0.0", false, List.of("alpha")),
                                        execToolPackage(common, "1.0.0", false, List.of("alpha")))),
                                Set.of()),
                        new WorkspaceMemberResolveOutput(
                                "apps/worker",
                                lockfile(List.of(
                                        execToolPackage(beta, "1.0.0", true, List.of("beta")),
                                        execToolPackage(shared, "2.0.0", false, List.of("beta")),
                                        execToolPackage(common, "1.0.0", false, List.of("beta")))),
                                Set.of())));

        // Both incompatible versions of the shared library survive, each tagged with its own tool.
        assertEquals(List.of("alpha"), execEntry(aggregated, shared, "1.0.0").toolGroups());
        assertEquals(List.of("apps/api"), execEntry(aggregated, shared, "1.0.0").members());
        assertEquals(List.of("beta"), execEntry(aggregated, shared, "2.0.0").toolGroups());
        assertEquals(List.of("apps/worker"), execEntry(aggregated, shared, "2.0.0").members());
        // A jar shared by both tools at the same version unions their groups (sorted).
        assertEquals(List.of("alpha", "beta"), execEntry(aggregated, common, "1.0.0").toolGroups());
        assertEquals(List.of("apps/api", "apps/worker"), execEntry(aggregated, common, "1.0.0").members());
        // Tool-owned jars keep their own group.
        assertEquals(List.of("alpha"), execEntry(aggregated, alpha, "1.0.0").toolGroups());
        assertEquals(List.of("beta"), execEntry(aggregated, beta, "1.0.0").toolGroups());
        // Divergent exec-tool versions are intentional isolation, never mediated into a conflict.
        assertEquals(List.of(), aggregated.conflicts());
        // The documented per-step selection (scope == TOOL_EXEC && toolGroups.contains(tool)) gives each
        // tool exactly its own locked version of the shared library, never the other tool's.
        assertEquals(Set.of("1.0.0"), execVersionsForTool(aggregated, shared, "alpha"));
        assertEquals(Set.of("2.0.0"), execVersionsForTool(aggregated, shared, "beta"));
    }

    @Test
    void unionsToolGroupsForExecJarSharedAcrossMembersAtSameVersion() throws IOException {
        Workspace workspace = workspace();
        PackageId common = new PackageId("com.example", "common-lib");

        ZoltLockfile aggregated = new WorkspaceLockfileAggregator().aggregate(
                workspace,
                List.of(
                        new WorkspaceMemberResolveOutput(
                                "apps/api",
                                lockfile(List.of(execToolPackage(common, "1.0.0", false, List.of("alpha")))),
                                Set.of()),
                        new WorkspaceMemberResolveOutput(
                                "apps/worker",
                                lockfile(List.of(execToolPackage(common, "1.0.0", false, List.of("beta")))),
                                Set.of())));

        List<LockPackage> commonEntries = aggregated.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(common))
                .toList();
        assertEquals(1, commonEntries.size());
        assertEquals(List.of("alpha", "beta"), commonEntries.getFirst().toolGroups());
        assertEquals(List.of("apps/api", "apps/worker"), commonEntries.getFirst().members());
    }

    @Test
    void aggregatedExecToolLockIsByteDeterministic() throws IOException {
        Workspace workspace = workspace();
        PackageId shared = new PackageId("com.example", "shared-lib");
        PackageId common = new PackageId("com.example", "common-lib");
        List<WorkspaceMemberResolveOutput> memberOutputs = List.of(
                new WorkspaceMemberResolveOutput(
                        "apps/api",
                        lockfile(List.of(
                                execToolPackage(shared, "1.0.0", false, List.of("alpha")),
                                execToolPackage(common, "1.0.0", false, List.of("alpha")))),
                        Set.of()),
                new WorkspaceMemberResolveOutput(
                        "apps/worker",
                        lockfile(List.of(
                                execToolPackage(shared, "2.0.0", false, List.of("beta")),
                                execToolPackage(common, "1.0.0", false, List.of("beta")))),
                        Set.of()));

        ZoltLockfileWriter writer = new ZoltLockfileWriter();
        String first = writer.write(new WorkspaceLockfileAggregator().aggregate(workspace, memberOutputs));
        String second = writer.write(new WorkspaceLockfileAggregator().aggregate(workspace, memberOutputs));

        assertEquals(first, second);
        assertEquals(1, first.split("toolGroups = \\[\"alpha\", \"beta\"\\]", -1).length - 1);
    }

    private Workspace workspace() throws IOException {
        Files.createDirectories(tempDir.resolve("apps/api"));
        Files.createDirectories(tempDir.resolve("apps/worker"));
        List<WorkspaceMember> members = List.of(
                new WorkspaceMember("apps/api", tempDir.resolve("apps/api"), config("api")),
                new WorkspaceMember("apps/worker", tempDir.resolve("apps/worker"), config("worker")));
        return new Workspace(
                tempDir,
                tempDir.resolve("zolt.toml"),
                new WorkspaceConfig(
                        "acme-platform",
                        members.stream().map(WorkspaceMember::path).toList(),
                        List.of(),
                        Map.of(),
                        Map.of()),
                members,
                List.of(),
                List.of("apps/api", "apps/worker"));
    }

    private static ZoltLockfile lockfile(List<LockPackage> packages) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.of("sha256:project"),
                List.of("repositories=sha256:repo"),
                packages,
                List.of(),
                List.of());
    }

    private static LockPackage execToolPackage(
            PackageId packageId,
            String version,
            boolean direct,
            List<String> toolGroups) {
        return new LockPackage(
                packageId,
                version,
                "central",
                DependencyScope.TOOL_EXEC,
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
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                toolGroups);
    }

    private static LockPackage execEntry(ZoltLockfile lockfile, PackageId packageId, String version) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.scope() == DependencyScope.TOOL_EXEC)
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .filter(lockPackage -> lockPackage.version().equals(version))
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> execVersionsForTool(ZoltLockfile lockfile, PackageId packageId, String tool) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.scope() == DependencyScope.TOOL_EXEC)
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .filter(lockPackage -> lockPackage.toolGroups().contains(tool))
                .map(LockPackage::version)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static ProjectConfig config(String name) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "0.1.0", "com.acme", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }
}
