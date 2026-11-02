package sh.zolt.workspace.service;

import sh.zolt.build.CompilationSemantics;
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
 * Captures member inputs before classpath construction. Resolution lanes supply external artifact
 * identities; workspace dependencies supply ABI digests for compilation and full content digests
 * wherever annotation processors execute their code. Sources, resources, generated outputs, and
 * toolchain identity are tracked separately so the planner can choose the required work.
 */
final class WorkspaceMemberStateObserver {
    private final WorkspaceExecutionContext context;
    private final Map<String, WorkspaceMember> membersByPath;
    private final String cacheRootDigest;
    private final Set<String> mainProcessorMembers;
    private final Set<String> testProcessorMembers;

    WorkspaceMemberStateObserver(
            WorkspaceExecutionContext context,
            Map<String, WorkspaceMember> membersByPath) {
        this.context = context;
        this.membersByPath = membersByPath;
        this.cacheRootDigest = WorkspaceHash.text(context.cacheRoot().toString());
        this.mainProcessorMembers = WorkspaceCanonicalBuildPolicy.membersWithProcessorInputs(
                context.workspace(), context.lockfile());
        this.testProcessorMembers = WorkspaceCanonicalBuildPolicy.membersWithTestProcessorInputs(
                context.workspace(), context.lockfile());
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
        var mainSources = snapshot.mainSources(
                path, WorkspaceFileKind.MAIN_SOURCE, member.directory(), build.sourceRoots());
        var resources = snapshot.resources(
                path, WorkspaceFileKind.MAIN_RESOURCE, member.directory(), build.resourceRoots());
        String configDigest = WorkspaceHash.text(member.config().toString());
        String toolchainDigest = toolchainDigest(member, resolvedToolchainIdentity, snapshot);
        String generatedDigest = generatedInputs(snapshot, member, build.generatedMainSources());
        String processorDigest = processorInputDigest(member);
        String compileKey = WorkspaceHash.text(String.join(
                "|",
                CompilationSemantics.VERSION,
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
     * The test lane's compile inputs and prior annotation-processor output. Test resources are
     * tracked beside this key rather than inside it, exactly as the main lane keeps
     * {@code resourceTreeDigest} out of its compile key: both lanes copy resources after compiling,
     * so a resource edit has to be able to say so on its own instead of masquerading as a source
     * change. Processor output is different: it is an owned output that becomes an input to the
     * next freshness decision, so the key must reject a hand edit or deletion before the canonical
     * compiler fingerprint can be bypassed.
     */
    String testCompileKey(WorkspaceMember member, String mainManifestDigest) {
        var build = member.config().build();
        var testSources = context.fileSnapshot().testSources(
                member.path(),
                WorkspaceFileKind.TEST_SOURCE,
                member.directory(),
                build.testSources(),
                build.groovyTestSources(),
                build.kotlinTestSources());
        return WorkspaceHash.text(String.join(
                "|",
                CompilationSemantics.VERSION,
                mainManifestDigest.isEmpty() ? "missing" : mainManifestDigest,
                testSources.digest(),
                generatedTestOutputDigest(context.fileSnapshot(), member),
                resolutionInputDigest(context.laneClosure().test(member.path())),
                abiDigest(context.memberGraph().test(member.path())),
                testProcessorCompileInputs(member)));
    }

    TestCompilationState successfulTestCompilation(
            WorkspaceMember member,
            String mainManifestDigest) {
        var build = member.config().build();
        context.fileSnapshot().forget(member.path(), WorkspaceFileKind.GENERATED_TEST_OUTPUT);
        String resources = context.fileSnapshot()
                .resources(
                        member.path(),
                        WorkspaceFileKind.TEST_RESOURCE,
                        member.directory(),
                        build.testResourceRoots())
                .digest();
        String manifest = context.abiIndex()
                .refreshTest(member.directory()
                        .resolve(build.testOutput())
                        .toAbsolutePath()
                        .normalize())
                .map(IncrementalCompileSummary::outputManifestDigest)
                .orElse("");
        return new TestCompilationState(
                testCompileKey(member, mainManifestDigest),
                resources,
                manifest);
    }

    int sourceCount(WorkspaceMember member) {
        return context.fileSnapshot()
                .mainSources(
                        member.path(),
                        WorkspaceFileKind.MAIN_SOURCE,
                        member.directory(),
                        member.config().build().sourceRoots())
                .fileCount();
    }

    /**
     * Tracks processor execution inputs separately from compile ABI. Classes, resources, and runtime
     * dependencies must remain visible even when a provider was rebuilt in a separate command.
     */
    private String processorInputDigest(WorkspaceMember member) {
        Set<String> processors = new TreeSet<>(WorkspaceCanonicalBuildPolicy.processorMembers(
                context.workspace(),
                member.path(),
                context.memberGraph().compileDependenciesByMember()));
        // javac also places the ordinary compile classpath on the processor path. Its code can
        // execute during processing, so implementation-only edits must reach this outer gate.
        if (mainProcessorMembers.contains(member.path())) {
            processors.addAll(context.memberGraph().mainCompile(member.path()));
        }
        return processorOutputsDigest(processors);
    }

    private String testProcessorCompileInputs(WorkspaceMember member) {
        if (!testProcessorMembers.contains(member.path())) {
            return "";
        }
        Set<String> inputs = new TreeSet<>(context.memberGraph().test(member.path()));
        inputs.add(member.path());
        return processorOutputsDigest(inputs);
    }

    private String processorOutputsDigest(Set<String> processors) {
        StringBuilder content = new StringBuilder();
        for (String processor : new TreeSet<>(processors)) {
            WorkspaceMember provider = membersByPath.get(processor);
            content.append(processor)
                    .append('|')
                    .append(provider == null ? "missing" : context.fileSnapshot()
                            .processorOutput(processor, provider.directory()
                                    .resolve(provider.config().build().output()))
                            .digest())
                    .append('|')
                    .append(context.laneClosure().mainRuntime(processor).digest())
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

    /** What test annotation processors emitted, observed separately from their main-lane twins. */
    private static String generatedTestOutputDigest(
            WorkspaceFileSnapshot snapshot,
            WorkspaceMember member) {
        Path generated = member.directory()
                .resolve(member.config().compilerSettings().generatedTestSources())
                .toAbsolutePath()
                .normalize();
        return snapshot.tree(
                        member.path(),
                        WorkspaceFileKind.GENERATED_TEST_OUTPUT,
                        generated)
                .digest();
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

    record TestCompilationState(
            String compileKey,
            String resourceTreeDigest,
            String outputManifestDigest) {
    }

}
