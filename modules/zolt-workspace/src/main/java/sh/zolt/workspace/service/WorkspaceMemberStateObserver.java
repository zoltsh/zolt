package sh.zolt.workspace.service;

import sh.zolt.build.incremental.IncrementalCompileSummary;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.workspace.resolve.WorkspaceMemberLaneClosure;
import sh.zolt.workspace.state.WorkspaceFileKind;
import sh.zolt.workspace.state.WorkspaceFileSnapshot;
import sh.zolt.workspace.state.WorkspaceHash;
import sh.zolt.workspace.state.WorkspaceMemberState;
import sh.zolt.workspace.state.WorkspaceState;
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
 * member's key for the lane. Processor-scoped packages are included in that: they sit in the member's
 * own lane bucket, so a processor version bump moves the compile key like any other dependency edit.
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
        String path = member.path();
        var mainSources = snapshot.javaSources(
                path, WorkspaceFileKind.MAIN_SOURCE, member.directory(), build.sourceRoots());
        var resources = snapshot.resources(
                path, WorkspaceFileKind.MAIN_RESOURCE, member.directory(), build.resourceRoots());
        String configDigest = WorkspaceHash.text(member.config().toString());
        String toolchainDigest = toolchainDigest(member, resolvedToolchainIdentity, snapshot);
        String generatedDigest = generatedInputs(snapshot, member, build.generatedMainSources());
        String processorDigest = processorInputDigest(member);
        String compileKey = WorkspaceHash.text(String.join(
                "|",
                configDigest,
                toolchainDigest,
                mainSources.digest(),
                generatedDigest,
                resolutionInputDigest(context.laneClosure().mainCompile(path)),
                abiDigest(context.memberGraph().mainCompile(path))));
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
            testResources = snapshot.resources(
                            path,
                            WorkspaceFileKind.TEST_RESOURCE,
                            member.directory(),
                            build.testResourceRoots())
                    .digest();
            testManifest = context.abiIndex()
                    .test(member.directory().resolve(build.testOutput()).toAbsolutePath().normalize())
                    .map(IncrementalCompileSummary::outputManifestDigest)
                    .orElse("");
        }
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
                processorDigest,
                generatedOutputDigest(snapshot, member));
    }

    /**
     * The test lane's compile inputs only. Test resources are tracked beside this key rather than
     * inside it, exactly as the main lane keeps {@code resourceTreeDigest} out of its compile key:
     * both lanes copy resources after compiling, so a resource edit has to be able to say so on its
     * own instead of masquerading as a source change.
     */
    String testCompileKey(WorkspaceMember member, String mainManifestDigest) {
        var build = member.config().build();
        var testSources = context.fileSnapshot().javaSources(
                member.path(), WorkspaceFileKind.TEST_SOURCE, member.directory(), build.testSources());
        return WorkspaceHash.text(String.join(
                "|",
                mainManifestDigest.isEmpty() ? "missing" : mainManifestDigest,
                testSources.digest(),
                resolutionInputDigest(context.laneClosure().test(member.path())),
                abiDigest(context.memberGraph().test(member.path()))));
    }

    int sourceCount(WorkspaceMember member) {
        return context.fileSnapshot()
                .javaSources(
                        member.path(),
                        WorkspaceFileKind.MAIN_SOURCE,
                        member.directory(),
                        member.config().build().sourceRoots())
                .fileCount();
    }

    /**
     * The identity of the classes that will run as this member's annotation processors, to the extent
     * that identity is not already inside the compile lane. External processor jars are inside it —
     * they are lock packages on the member's own lane bucket. Workspace processor members are not,
     * because they are deliberately kept out of the compile closure so their classes never leak onto
     * a compile classpath, so their output is folded in here instead.
     *
     * <p>A processor member is folded in by its whole compiled output rather than by its ABI. An ABI
     * is the right currency for a compile dependency, because javac reads only signatures from one —
     * but a processor is a program that runs, and an edit confined to a method body can change every
     * source it emits while leaving its signatures untouched.
     *
     * <p>This is recorded beside the compile key rather than inside it so that a processor moving is
     * reported as a processor change and not as an unexplained lock edit.
     */
    private String processorInputDigest(WorkspaceMember member) {
        Set<String> processors = new TreeSet<>(WorkspaceCanonicalBuildPolicy.processorMembers(
                context.workspace(),
                member.path(),
                context.memberGraph().compileDependenciesByMember()));
        StringBuilder content = new StringBuilder();
        for (String processor : processors) {
            content.append(processor)
                    .append('|')
                    .append(mainSummary(processor)
                            .map(IncrementalCompileSummary::outputManifestDigest)
                            .orElse("missing"))
                    .append('\n');
        }
        return WorkspaceHash.text(content.toString());
    }

    /**
     * What the member's processors last emitted, recorded as the member's own observed state rather
     * than stood in for by a permanent dirty flag. Generated sources are outputs that are also inputs
     * to the next compile, so a hand edit or a deleted generated tree has to move a digest to be seen
     * at all: a member declared clean never reaches the build's own fingerprint gate.
     */
    private static String generatedOutputDigest(
            WorkspaceFileSnapshot snapshot,
            WorkspaceMember member) {
        Path generated = member.directory()
                .resolve(member.config().compilerSettings().generatedSources())
                .toAbsolutePath()
                .normalize();
        return snapshot.tree(member.path(), WorkspaceFileKind.GENERATED_OUTPUT, generated).digest();
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
                        + configHash(snapshot, member.path(), member.directory().resolve("zolt.toml"))
                        + "|workspaceConfig="
                        + configHash(snapshot, "", context.workspace().configPath())
                        + "|inheritedToolchainConfig="
                        + configHash(snapshot, "", context.workspace().root().resolve("zolt.toml")));
    }

    private static String configHash(WorkspaceFileSnapshot snapshot, String member, Path path) {
        return snapshot.pathHash(member, WorkspaceFileKind.CONFIG, path);
    }

    private String abiDigest(Set<String> memberPaths) {
        StringBuilder content = new StringBuilder();
        for (String dependency : new TreeSet<>(memberPaths)) {
            content.append(dependency).append('|').append(currentCompileAbi(dependency)).append('\n');
        }
        return WorkspaceHash.text(content.toString());
    }

    private String currentCompileAbi(String memberPath) {
        if (!membersByPath.containsKey(memberPath)) {
            return "unknown";
        }
        return mainSummary(memberPath)
                .map(summary -> WorkspaceHash.text(
                        summary.publicAbiDigest() + "|" + summary.packagePrivateAbiDigest()))
                .orElse("missing");
    }

    private Optional<IncrementalCompileSummary> mainSummary(String memberPath) {
        WorkspaceMember dependency = membersByPath.get(memberPath);
        if (dependency == null) {
            return Optional.empty();
        }
        return context.abiIndex().main(dependency.directory()
                .resolve(dependency.config().build().output())
                .toAbsolutePath()
                .normalize());
    }

    private static String generatedInputs(
            WorkspaceFileSnapshot snapshot,
            WorkspaceMember member,
            List<GeneratedSourceStep> steps) {
        List<Path> inputs = steps.stream()
                .flatMap(step -> step.inputs().stream())
                .map(input -> member.directory().resolve(input).normalize())
                .toList();
        return WorkspaceHash.text(steps + "|"
                + snapshot.paths(
                                member.path(),
                                WorkspaceFileKind.GENERATED_INPUT,
                                member.directory(),
                                inputs)
                        .digest());
    }

}
