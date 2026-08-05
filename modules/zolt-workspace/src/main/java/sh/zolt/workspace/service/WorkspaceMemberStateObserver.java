package sh.zolt.workspace.service;

import sh.zolt.build.incremental.IncrementalCompileSummary;
import sh.zolt.project.GeneratedSourceStep;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Captures a member's build-input state without constructing a single classpath.
 *
 * <p>The compile key previously folded in a digest over every entry of the member's compile and
 * processor classpaths, which meant the whole workspace had to be projected out of the root lock
 * before anything could be declared clean. The same two things move a member's classpath:
 *
 * <ul>
 *   <li>the external side, which changes only when the root lock changes — one digest for the whole
 *       workspace, taken from the planning bytes the planner already read; and
 *   <li>the workspace side, which changes only when a dependency member's ABI changes — read from
 *       the incremental-compile summaries Zolt already writes next to each member's output.
 * </ul>
 *
 * <p>Both are strictly cheaper than projecting a lock view and hashing its artifacts, and together
 * they cover exactly what the classpath digest covered for the purpose of deciding dirtiness.
 *
 * <p>The external half is asked of {@link WorkspaceMemberLaneClosure}, the same object the classpath
 * factory projects lanes from, so a lock edit that moves any lane of a member necessarily moves that
 * member's key for the lane.
 */
final class WorkspaceMemberStateObserver {
    private final WorkspaceExecutionContext context;
    private final Map<String, WorkspaceMember> membersByPath;
    private final String cacheRootDigest;

    WorkspaceMemberStateObserver(
            WorkspaceExecutionContext context,
            Map<String, WorkspaceMember> membersByPath) {
        this.context = context;
        this.membersByPath = membersByPath;
        this.cacheRootDigest = WorkspaceHash.text(context.cacheRoot().toString());
    }

    private String resolutionInputDigest(WorkspaceMemberLaneClosure.Lane lane) {
        return WorkspaceHash.text(cacheRootDigest + "|" + lane.digest());
    }

    /**
     * True when a workspace compile dependency's ABI on disk no longer matches the token the
     * dependent recorded for it, which is the workspace half of a moved compile classpath.
     */
    boolean dependencyAbiChanged(
            String memberPath,
            WorkspaceState previous) {
        for (String dependency : context.memberGraph().mainCompile(memberPath)) {
            Optional<WorkspaceMemberState> recorded = previous.member(dependency);
            if (recorded.isEmpty()) {
                return true;
            }
            if (!recorded.orElseThrow().compileAbiDigest().equals(currentCompileAbi(dependency))) {
                return true;
            }
        }
        return false;
    }

    WorkspaceMemberState observe(
            WorkspaceMember member,
            WorkspaceBuildRequirements requirements,
            String resolvedToolchainIdentity,
            Optional<WorkspaceMemberState> previous) {
        WorkspaceFileSnapshot snapshot = context.fileSnapshot();
        var build = member.config().build();
        var mainSources = snapshot.javaSources(member.directory(), build.sourceRoots());
        var resources = snapshot.resources(member.directory(), build.resourceRoots());
        String configDigest = WorkspaceHash.text(member.config().toString());
        String toolchainDigest = toolchainDigest(member, resolvedToolchainIdentity, snapshot);
        String generatedDigest = generatedInputs(snapshot, member, build.generatedMainSources());
        Set<String> compileClosure = context.memberGraph().mainCompile(member.path());
        String compileKey = WorkspaceHash.text(String.join(
                "|",
                configDigest,
                toolchainDigest,
                mainSources.digest(),
                generatedDigest,
                resolutionInputDigest(context.laneClosure().mainCompile(member.path())),
                abiDigest(compileClosure)));
        Path mainOutput = member.directory().resolve(build.output()).toAbsolutePath().normalize();
        Optional<IncrementalCompileSummary> mainSummary = context.abiIndex().main(mainOutput);
        String mainManifest = mainSummary
                .map(IncrementalCompileSummary::outputManifestDigest)
                .orElse("");
        String testCompileKey = previous.map(WorkspaceMemberState::testCompileKey).orElse("");
        String testResources =
                previous.map(WorkspaceMemberState::testResourceTreeDigest).orElse("");
        String testManifest = previous.map(WorkspaceMemberState::testOutputManifestDigest).orElse("");
        if (requirements.testCompileClasspath()) {
            testCompileKey = testCompileKey(member, mainManifest);
            testResources = snapshot.resources(member.directory(), build.testResourceRoots()).digest();
            testManifest = context.abiIndex()
                    .test(member.directory().resolve(build.testOutput()).toAbsolutePath().normalize())
                    .map(IncrementalCompileSummary::outputManifestDigest)
                    .orElse("");
        }
        String packageKey = requirements.packageInputs()
                ? packageKey(member, resources.digest(), mainManifest)
                : previous.map(WorkspaceMemberState::packageKey).orElse("");
        return new WorkspaceMemberState(
                configDigest,
                toolchainDigest,
                mainSources.digest(),
                resources.digest(),
                generatedDigest,
                compileKey,
                mainManifest,
                mainSummary.map(IncrementalCompileSummary::publicAbiDigest).orElse(""),
                mainSummary.map(IncrementalCompileSummary::packagePrivateAbiDigest).orElse(""),
                testCompileKey,
                testResources,
                testManifest,
                packageKey);
    }

    /**
     * The test lane's compile inputs only. Test resources are tracked beside this key rather than
     * inside it, exactly as the main lane keeps {@code resourceTreeDigest} out of its compile key:
     * both lanes copy resources after compiling, so a resource edit has to be able to say so on its
     * own instead of masquerading as a source change.
     */
    String testCompileKey(WorkspaceMember member, String mainManifestDigest) {
        var build = member.config().build();
        var testSources = context.fileSnapshot().javaSources(member.directory(), build.testSources());
        Set<String> testClosure = context.memberGraph().test(member.path());
        return WorkspaceHash.text(String.join(
                "|",
                mainManifestDigest.isEmpty() ? "missing" : mainManifestDigest,
                testSources.digest(),
                resolutionInputDigest(context.laneClosure().test(member.path())),
                abiDigest(testClosure)));
    }

    int sourceCount(WorkspaceMember member) {
        return context.fileSnapshot()
                .javaSources(member.directory(), member.config().build().sourceRoots())
                .fileCount();
    }

    private String packageKey(
            WorkspaceMember member,
            String resourceDigest,
            String mainManifestDigest) {
        Set<String> runtimeClosure = context.memberGraph().mainRuntime(member.path());
        return WorkspaceHash.text(String.join(
                "|",
                member.config().packageSettings().toString(),
                resourceDigest,
                mainManifestDigest.isEmpty() ? "missing" : mainManifestDigest,
                resolutionInputDigest(context.laneClosure().mainRuntime(member.path())),
                abiDigest(runtimeClosure)));
    }

    private String toolchainDigest(
            WorkspaceMember member,
            String resolvedToolchainIdentity,
            WorkspaceFileSnapshot snapshot) {
        return WorkspaceHash.text(
                member.config().project().java()
                        + "|"
                        + member.config().compilerSettings()
                        + "|"
                        + resolvedToolchainIdentity
                        + "|memberConfig="
                        + snapshot.pathHash(member.directory().resolve("zolt.toml"))
                        + "|workspaceConfig="
                        + snapshot.pathHash(context.workspace().configPath())
                        + "|inheritedToolchainConfig="
                        + snapshot.pathHash(context.workspace().root().resolve("zolt.toml")));
    }

    private String abiDigest(Set<String> memberPaths) {
        StringBuilder content = new StringBuilder();
        for (String dependency : new TreeSet<>(memberPaths)) {
            content.append(dependency).append('|').append(currentCompileAbi(dependency)).append('\n');
        }
        return WorkspaceHash.text(content.toString());
    }

    private String currentCompileAbi(String memberPath) {
        WorkspaceMember dependency = membersByPath.get(memberPath);
        if (dependency == null) {
            return "unknown";
        }
        Path output = dependency.directory()
                .resolve(dependency.config().build().output())
                .toAbsolutePath()
                .normalize();
        return context.abiIndex()
                .main(output)
                .map(summary -> WorkspaceHash.text(
                        summary.publicAbiDigest() + "|" + summary.packagePrivateAbiDigest()))
                .orElse("missing");
    }

    private static String generatedInputs(
            WorkspaceFileSnapshot snapshot,
            WorkspaceMember member,
            List<GeneratedSourceStep> steps) {
        List<Path> inputs = steps.stream()
                .flatMap(step -> step.inputs().stream())
                .map(input -> member.directory().resolve(input).normalize())
                .toList();
        return WorkspaceHash.text(
                steps + "|" + snapshot.paths(member.directory(), inputs).digest());
    }

}
