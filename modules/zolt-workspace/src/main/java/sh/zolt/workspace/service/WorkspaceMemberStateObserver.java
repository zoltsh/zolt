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
 */
final class WorkspaceMemberStateObserver {
    private final WorkspaceExecutionContext context;
    private final Map<String, WorkspaceMember> membersByPath;
    private final String resolutionInputDigest;

    WorkspaceMemberStateObserver(
            WorkspaceExecutionContext context,
            Map<String, WorkspaceMember> membersByPath) {
        this.context = context;
        this.membersByPath = membersByPath;
        this.resolutionInputDigest = resolutionInputDigest(context);
    }

    String resolutionInputDigest() {
        return resolutionInputDigest;
    }

    /**
     * The digest of every workspace compile dependency's current on-disk ABI. Compared against the
     * ABI each dependency recorded in the previous state, this is the dependency-ABI token gate.
     */
    String dependencyAbiDigest(String memberPath) {
        return abiDigest(context.memberGraph().mainCompile(memberPath));
    }

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
        String compileKey = WorkspaceHash.text(String.join(
                "|",
                configDigest,
                toolchainDigest,
                mainSources.digest(),
                generatedDigest,
                resolutionInputDigest,
                dependencyAbiDigest(member.path())));
        Path mainOutput = member.directory().resolve(build.output()).toAbsolutePath().normalize();
        Optional<IncrementalCompileSummary> mainSummary = context.abiIndex().main(mainOutput);
        String mainManifest = mainSummary
                .map(IncrementalCompileSummary::outputManifestDigest)
                .orElse("");
        String testCompileKey = previous.map(WorkspaceMemberState::testCompileKey).orElse("");
        String testManifest = previous.map(WorkspaceMemberState::testOutputManifestDigest).orElse("");
        if (requirements.testCompileClasspath()) {
            testCompileKey = testCompileKey(member, mainManifest);
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
                testManifest,
                packageKey);
    }

    String testCompileKey(WorkspaceMember member, String mainManifestDigest) {
        var build = member.config().build();
        var testSources = context.fileSnapshot().javaSources(member.directory(), build.testSources());
        return WorkspaceHash.text(String.join(
                "|",
                mainManifestDigest.isEmpty() ? "missing" : mainManifestDigest,
                testSources.digest(),
                resolutionInputDigest,
                abiDigest(context.memberGraph().test(member.path()))));
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
        return WorkspaceHash.text(String.join(
                "|",
                member.config().packageSettings().toString(),
                resourceDigest,
                mainManifestDigest.isEmpty() ? "missing" : mainManifestDigest,
                resolutionInputDigest,
                abiDigest(context.memberGraph().mainRuntime(member.path()))));
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

    /**
     * One digest for the whole workspace: the cache root the artifacts resolve under plus the exact
     * root-lock bytes the planner captured, falling back to reading the file when a caller built a
     * context without planning inputs.
     */
    private static String resolutionInputDigest(WorkspaceExecutionContext context) {
        Path lockfilePath = context.workspace().root().resolve("zolt.lock");
        String lockDigest = context.workspace().inputs()
                .contentBytes(lockfilePath)
                .map(WorkspaceHash::bytes)
                .orElseGet(() -> context.fileSnapshot().pathHash(lockfilePath));
        return WorkspaceHash.text(context.cacheRoot() + "|" + lockDigest);
    }
}
