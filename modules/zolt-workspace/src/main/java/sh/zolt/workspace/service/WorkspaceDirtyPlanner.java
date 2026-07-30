package sh.zolt.workspace.service;

import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.build.incremental.IncrementalCompileSummary;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.PackageMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkspaceDirtyPlanner {
    private final WorkspaceStateStore stateStore = new WorkspaceStateStore();
    private final IncrementalCompileStateCodec compileStateCodec =
            new IncrementalCompileStateCodec();

    WorkspaceDirtyPlan plan(
            WorkspaceExecutionContext context,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, ClasspathSet> classpathsByMember,
            Map<String, List<ResolvedClasspathPackage>> packagesByMember,
            Map<String, WorkspaceBuildRequirements> requirementsByMember,
            Map<String, String> toolchainIdentitiesByMember) {
        WorkspaceState previous = stateStore.read(context.workspace().root());
        long started = System.nanoTime();
        Map<String, WorkspaceDirtyPlan.MemberPlan> plans = new LinkedHashMap<>();
        for (String memberPath : selection.includedMembers()) {
            WorkspaceMember member = membersByPath.get(memberPath);
            Optional<WorkspaceMemberState> prior = previous.member(memberPath);
            WorkspaceMemberState candidate = observe(
                    context,
                    member,
                    classpathsByMember.get(memberPath),
                    packagesByMember.getOrDefault(memberPath, List.of()),
                    requirementsByMember.getOrDefault(
                            memberPath,
                            WorkspaceBuildRequirements.mainBuild()),
                    toolchainIdentitiesByMember.getOrDefault(memberPath, ""),
                    prior);
            List<String> reasons = dirtyReasons(
                    context.fileSnapshot(),
                    member,
                    classpathsByMember.get(memberPath),
                    prior,
                    candidate);
            plans.put(
                    memberPath,
                    new WorkspaceDirtyPlan.MemberPlan(
                            candidate,
                            prior,
                            sourceCount(context.fileSnapshot(), member),
                            !reasons.isEmpty(),
                            reasons));
        }
        context.addFileSnapshotMetrics(
                Math.max(0L, System.nanoTime() - started),
                context.fileSnapshot().bytesHashed(),
                context.fileSnapshot().filesHashed());
        return new WorkspaceDirtyPlan(previous, plans);
    }

    void writeCurrent(
            WorkspaceExecutionContext context,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, ClasspathSet> classpathsByMember,
            Map<String, List<ResolvedClasspathPackage>> packagesByMember,
            Map<String, WorkspaceBuildRequirements> requirementsByMember,
            Map<String, String> toolchainIdentitiesByMember,
            WorkspaceDirtyPlan plan) {
        Map<String, WorkspaceMemberState> current =
                new LinkedHashMap<>(plan.previousState().members());
        for (String memberPath : selection.includedMembers()) {
            WorkspaceMember member = membersByPath.get(memberPath);
            context.abiIndex().refreshMain(
                    member.directory().resolve(member.config().build().output()));
            if (requirementsByMember
                    .getOrDefault(memberPath, WorkspaceBuildRequirements.mainBuild())
                    .testCompileClasspath()) {
                context.abiIndex().refreshTest(
                        member.directory().resolve(member.config().build().testOutput()));
            }
            current.put(
                    memberPath,
                    observe(
                            context,
                            member,
                            classpathsByMember.get(memberPath),
                            packagesByMember.getOrDefault(memberPath, List.of()),
                            requirementsByMember.getOrDefault(
                                    memberPath,
                                    WorkspaceBuildRequirements.mainBuild()),
                            toolchainIdentitiesByMember.getOrDefault(memberPath, ""),
                            plan.previousState().member(memberPath)));
        }
        stateStore.write(context.workspace().root(), new WorkspaceState(current));
    }

    private WorkspaceMemberState observe(
            WorkspaceExecutionContext context,
            WorkspaceMember member,
            ClasspathSet classpaths,
            List<ResolvedClasspathPackage> packages,
            WorkspaceBuildRequirements requirements,
            String resolvedToolchainIdentity,
            Optional<WorkspaceMemberState> previous) {
        WorkspaceFileSnapshot snapshot = context.fileSnapshot();
        var build = member.config().build();
        var mainSources = snapshot.javaSources(member.directory(), build.sourceRoots());
        var resources = snapshot.resources(member.directory(), build.resourceRoots());
        String configDigest = WorkspaceHash.text(member.config().toString());
        String toolchainDigest = WorkspaceHash.text(
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
                        + snapshot.pathHash(context.workspace().root().resolve("zolt.toml"))
                        + "|toolchainLock="
                        + snapshot.pathHash(context.workspace().root().resolve("zolt.lock")));
        String generatedDigest = generatedInputs(snapshot, member, build.generatedMainSources());
        String compileClasspathDigest =
                classpathDigest(context, snapshot, classpaths.compile());
        String processorClasspathDigest =
                classpathDigest(context, snapshot, classpaths.processor());
        String compileKey = WorkspaceHash.text(String.join(
                "|",
                configDigest,
                toolchainDigest,
                mainSources.digest(),
                generatedDigest,
                compileClasspathDigest,
                processorClasspathDigest));
        Path mainOutput = member.directory().resolve(build.output()).toAbsolutePath().normalize();
        Optional<IncrementalCompileSummary> mainSummary =
                context.abiIndex().main(mainOutput);
        String testCompileKey = previous.map(WorkspaceMemberState::testCompileKey).orElse("");
        String testManifest = previous.map(WorkspaceMemberState::testOutputManifestDigest).orElse("");
        if (requirements.testCompileClasspath()) {
            var testSources = snapshot.javaSources(member.directory(), build.testSources());
            Path testOutput = member.directory().resolve(build.testOutput()).toAbsolutePath().normalize();
            Optional<IncrementalCompileSummary> testSummary =
                    context.abiIndex().test(testOutput);
            testCompileKey = WorkspaceHash.text(
                    mainSummary.map(IncrementalCompileSummary::outputManifestDigest).orElse("missing")
                            + "|"
                            + testSources.digest()
                            + "|"
                            + classpathDigest(context, snapshot, classpaths.testCompile())
                            + "|"
                            + classpathDigest(context, snapshot, classpaths.testProcessor()));
            testManifest = testSummary
                    .map(IncrementalCompileSummary::outputManifestDigest)
                    .orElse("");
        }
        String packageKey = requirements.packageInputs()
                ? packageKey(member, packages, resources.digest(), mainSummary)
                : previous.map(WorkspaceMemberState::packageKey).orElse("");
        return new WorkspaceMemberState(
                configDigest,
                toolchainDigest,
                mainSources.digest(),
                resources.digest(),
                generatedDigest,
                compileKey,
                mainSummary.map(IncrementalCompileSummary::outputManifestDigest).orElse(""),
                mainSummary.map(IncrementalCompileSummary::publicAbiDigest).orElse(""),
                mainSummary.map(IncrementalCompileSummary::packagePrivateAbiDigest).orElse(""),
                testCompileKey,
                testManifest,
                packageKey);
    }

    private List<String> dirtyReasons(
            WorkspaceFileSnapshot snapshot,
            WorkspaceMember member,
            ClasspathSet classpaths,
            Optional<WorkspaceMemberState> previous,
            WorkspaceMemberState candidate) {
        List<String> reasons = new ArrayList<>();
        if (previous.isEmpty()) {
            reasons.add("missing-workspace-state");
        } else {
            WorkspaceMemberState prior = previous.orElseThrow();
            if (!prior.mainCompileKey().equals(candidate.mainCompileKey())) {
                reasons.add("main-compile-key-changed");
            }
            if (!prior.resourceTreeDigest().equals(candidate.resourceTreeDigest())) {
                reasons.add("resource-key-changed");
            }
        }
        if (!compileOutputsCurrent(member)) {
            reasons.add("main-output-missing-or-stale");
        }
        if (!snapshot.resourceOutputsCurrent(member.directory(), member.config().build())) {
            reasons.add("resource-output-missing-or-stale");
        }
        if (WorkspaceCanonicalBuildPolicy.hasGeneratedInputs(member, classpaths)) {
            reasons.add("conservative-generated-input");
        }
        if (WorkspaceCanonicalBuildPolicy.hasFrameworkOutputs(member)) {
            reasons.add("conservative-framework-output");
        }
        return List.copyOf(reasons);
    }

    private boolean compileOutputsCurrent(WorkspaceMember member) {
        if (member.config().packageSettings().mode() == PackageMode.BOM) {
            return true;
        }
        Path output = member.directory()
                .resolve(member.config().build().output())
                .toAbsolutePath()
                .normalize();
        Optional<IncrementalCompileState> state =
                compileStateCodec.read(IncrementalCompileState.mainStatePath(output));
        return state.isPresent()
                && state.orElseThrow().outputDirectory().equals(output)
                && state.orElseThrow().classes().stream()
                        .map(IncrementalCompileState.ClassRecord::outputPath)
                        .allMatch(Files::isRegularFile);
    }

    private String classpathDigest(
            WorkspaceExecutionContext context,
            WorkspaceFileSnapshot snapshot,
            Classpath classpath) {
        StringBuilder content = new StringBuilder();
        classpath.entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted()
                .forEach(path -> content
                        .append(path)
                        .append('|')
                        .append(context.abiIndex()
                                .main(path)
                                .map(IncrementalCompileSummary::compileAbiDigest)
                                .orElseGet(() -> snapshot.pathHash(path)))
                        .append('\n'));
        return WorkspaceHash.text(content.toString());
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

    private static int sourceCount(
            WorkspaceFileSnapshot snapshot,
            WorkspaceMember member) {
        return snapshot.javaSources(
                        member.directory(),
                        member.config().build().sourceRoots())
                .fileCount();
    }

    private static String packageKey(
            WorkspaceMember member,
            List<ResolvedClasspathPackage> packages,
            String resourceDigest,
            Optional<IncrementalCompileSummary> summary) {
        return WorkspaceHash.text(
                member.config().packageSettings()
                        + "|"
                        + resourceDigest
                        + "|"
                        + summary.map(IncrementalCompileSummary::outputManifestDigest).orElse("missing")
                        + "|"
                        + packages);
    }
}
