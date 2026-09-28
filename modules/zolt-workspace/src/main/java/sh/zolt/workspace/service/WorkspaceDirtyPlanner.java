package sh.zolt.workspace.service;

import sh.zolt.build.fingerprint.BuildFingerprintOutputValidator;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.project.PackageMode;
import sh.zolt.workspace.state.WorkspaceFileKind;
import sh.zolt.workspace.state.WorkspaceMemberState;
import sh.zolt.workspace.state.WorkspaceState;
import sh.zolt.workspace.state.WorkspaceStateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stage 0 of workspace planning: decides, per member and from persisted state alone, whether the
 * member needs work — before any classpath, lock projection, or package list has been built.
 */
final class WorkspaceDirtyPlanner {
    private final WorkspaceStateStore stateStore = new WorkspaceStateStore();
    private final IncrementalCompileStateCodec compileStateCodec =
            new IncrementalCompileStateCodec();
    private final BuildFingerprintOutputValidator fingerprintOutputs =
            new BuildFingerprintOutputValidator();

    WorkspaceDirtyPlan plan(
            WorkspaceExecutionContext context,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, WorkspaceBuildRequirements> requirementsByMember,
            Map<String, String> toolchainIdentitiesByMember) {
        WorkspaceState previous = context.previousState();
        long started = System.nanoTime();
        WorkspaceMemberStateObserver observer =
                new WorkspaceMemberStateObserver(context, membersByPath);
        Map<String, WorkspaceDirtyPlan.MemberPlan> plans = new LinkedHashMap<>();
        for (String memberPath : selection.includedMembers()) {
            WorkspaceMember member = membersByPath.get(memberPath);
            WorkspaceBuildRequirements requirements = requirementsByMember.getOrDefault(
                    memberPath,
                    WorkspaceBuildRequirements.mainBuild());
            Optional<WorkspaceMemberState> prior = previous.member(memberPath);
            WorkspaceMemberState candidate = observer.observe(
                    member,
                    requirements,
                    toolchainIdentitiesByMember.getOrDefault(memberPath, ""),
                    prior);
            plans.put(
                    memberPath,
                    new WorkspaceDirtyPlan.MemberPlan(
                            candidate,
                            prior,
                            observer.sourceCount(member),
                            reasons(
                                    context,
                                    observer,
                                    member,
                                    requirements,
                                    previous,
                                    prior,
                                    candidate)));
        }
        context.addFileSnapshotMetrics(
                Math.max(0L, System.nanoTime() - started),
                context.fileSnapshot());
        return new WorkspaceDirtyPlan(
                previous,
                WorkspacePendingRebuildPropagator.propagate(
                        context,
                        plans,
                        requirementsByMember));
    }

    /**
     * Carries clean members' state forward untouched — nothing about them was observed to change,
     * so re-observing would recompute the identical row — and re-observes only what was executed.
     */
    void writeCurrent(
            WorkspaceExecutionContext context,
            WorkspaceSelection selection,
            Map<String, WorkspaceMember> membersByPath,
            Map<String, WorkspaceBuildRequirements> requirementsByMember,
            Map<String, String> toolchainIdentitiesByMember,
            WorkspaceDirtyPlan plan,
            Set<String> executedMembers,
            Set<String> pendingTestCompiles) {
        Map<String, WorkspaceMemberState> current =
                new LinkedHashMap<>(plan.previousState().members());
        WorkspaceMemberStateObserver observer =
                new WorkspaceMemberStateObserver(context, membersByPath);
        for (String memberPath : selection.includedMembers()) {
            WorkspaceMember member = membersByPath.get(memberPath);
            WorkspaceBuildRequirements requirements = requirementsByMember.getOrDefault(
                    memberPath,
                    WorkspaceBuildRequirements.mainBuild());
            if (!executedMembers.contains(memberPath)) {
                current.put(
                        memberPath,
                        preservePendingTestLane(
                                plan.member(memberPath).candidateState(),
                                plan.previousState().member(memberPath),
                                pendingTestCompiles.contains(memberPath)));
                continue;
            }
            context.fileSnapshot().forget(memberPath, WorkspaceFileKind.GENERATED_OUTPUT);
            context.abiIndex().refreshMain(
                    member.directory().resolve(member.config().build().output()));
            if (requirements.testCompileClasspath()) {
                context.abiIndex().refreshTest(
                        member.directory().resolve(member.config().build().testOutput()));
            }
            WorkspaceMemberState observed = observer.observe(
                    member,
                    requirements,
                    toolchainIdentitiesByMember.getOrDefault(memberPath, ""),
                    plan.previousState().member(memberPath));
            current.put(
                    memberPath,
                    preservePendingTestLane(
                            observed,
                            plan.previousState().member(memberPath),
                            pendingTestCompiles.contains(memberPath)));
        }
        stateStore.write(
                context.workspace().root(),
                new WorkspaceState(current, context.fileSnapshot().state()));
    }

    private static WorkspaceMemberState preservePendingTestLane(
            WorkspaceMemberState observed,
            Optional<WorkspaceMemberState> previous,
            boolean pending) {
        if (!pending) {
            return observed;
        }
        return observed.withTestCompilation(
                previous.map(WorkspaceMemberState::testCompileKey).orElse(""),
                previous.map(WorkspaceMemberState::testResourceTreeDigest).orElse(""),
                previous.map(WorkspaceMemberState::testOutputManifestDigest).orElse(""));
    }

    private List<WorkspaceDirtyReason> reasons(
            WorkspaceExecutionContext context,
            WorkspaceMemberStateObserver observer,
            WorkspaceMember member,
            WorkspaceBuildRequirements requirements,
            WorkspaceState previousState,
            Optional<WorkspaceMemberState> previous,
            WorkspaceMemberState candidate) {
        List<WorkspaceDirtyReason> reasons = new ArrayList<>();
        if (previous.isEmpty()) {
            reasons.add(WorkspaceDirtyReason.MISSING_STATE);
        } else {
            stateReasons(observer, member, previousState, previous.orElseThrow(), candidate, reasons);
        }
        if (!compileOutputsCurrent(member)) {
            reasons.add(WorkspaceDirtyReason.OUTPUT_MISSING);
        }
        if (!context.fileSnapshot()
                .resourceOutputsCurrent(member.path(), member.directory(), member.config().build())) {
            reasons.add(WorkspaceDirtyReason.RESOURCE_OUTPUT_MISSING);
        }
        if (!member.config().build().generatedMainSources().isEmpty()) {
            reasons.add(WorkspaceDirtyReason.CONSERVATIVE_GENERATED_SOURCE_STEP);
        }
        if (WorkspaceCanonicalBuildPolicy.hasFrameworkOutputs(member)) {
            reasons.add(WorkspaceDirtyReason.CONSERVATIVE_FRAMEWORK_OUTPUT);
        }
        if (WorkspaceCanonicalBuildPolicy.generatesBuildMetadata(member)) {
            reasons.add(WorkspaceDirtyReason.BUILD_METADATA_REQUIRED);
        }
        if (requirements.testCompileClasspath()) {
            testReasons(context, member, previous, candidate, reasons);
        }
        return List.copyOf(reasons);
    }

    private static void stateReasons(
            WorkspaceMemberStateObserver observer,
            WorkspaceMember member,
            WorkspaceState previousState,
            WorkspaceMemberState prior,
            WorkspaceMemberState candidate,
            List<WorkspaceDirtyReason> reasons) {
        int before = reasons.size();
        if (!prior.configDigest().equals(candidate.configDigest())) {
            reasons.add(WorkspaceDirtyReason.CONFIG_CHANGED);
        }
        if (!prior.toolchainDigest().equals(candidate.toolchainDigest())) {
            reasons.add(WorkspaceDirtyReason.TOOLCHAIN_CHANGED);
        }
        if (!prior.mainSourceTreeDigest().equals(candidate.mainSourceTreeDigest())) {
            reasons.add(WorkspaceDirtyReason.MAIN_SOURCE_CHANGED);
        }
        if (!prior.generatedInputDigest().equals(candidate.generatedInputDigest())) {
            reasons.add(WorkspaceDirtyReason.GENERATED_SOURCE_CHANGED);
        }
        if (moved(prior.generatedOutputDigest(), candidate.generatedOutputDigest())) {
            reasons.add(WorkspaceDirtyReason.GENERATED_OUTPUT_CHANGED);
        }
        if (moved(prior.processorInputDigest(), candidate.processorInputDigest())) {
            reasons.add(WorkspaceDirtyReason.PROCESSOR_INPUT_CHANGED);
        }
        if (observer.dependencyAbiChanged(member.path(), previousState)) {
            reasons.add(WorkspaceDirtyReason.DEPENDENCY_ABI_CHANGED);
        }
        if (observedMoved(
                prior.mainOutputManifestDigest(),
                candidate.mainOutputManifestDigest())) {
            reasons.add(WorkspaceDirtyReason.OUTPUT_CHANGED);
        }
        boolean compileKeyChanged = !prior.mainCompileKey().equals(candidate.mainCompileKey());
        if (compileKeyChanged && reasons.size() == before) {
            // Everything else the compile key covers matched, so the root lock is what moved.
            reasons.add(WorkspaceDirtyReason.RESOLUTION_INPUT_CHANGED);
        }
        if (!prior.resourceTreeDigest().equals(candidate.resourceTreeDigest())) {
            reasons.add(WorkspaceDirtyReason.RESOURCE_CHANGED);
        }
    }

    /**
     * Whether a recorded digest disagrees with a freshly observed one. An empty recorded value means
     * the field post-dates the state file that was read, not that the input was empty — every digest
     * is a hash and no hash is the empty string — so it is treated as unobserved rather than as a
     * mismatch. That is what lets an appended field arrive without invalidating the workspace.
     */
    private static boolean moved(String recorded, String observed) {
        return !recorded.isEmpty() && !recorded.equals(observed);
    }

    /**
     * A missing inner state is reported by the lane's existing output-missing reason. Once an
     * inner state has an output manifest, however, an empty outer value cannot prove that those
     * outputs were committed by the workspace transaction. Fail closed and reconcile the lane.
     */
    private static boolean observedMoved(String recorded, String observed) {
        return !observed.isEmpty() && !recorded.equals(observed);
    }

    /**
     * The test lane's half of stage 0, mirroring the main lane reason for reason: sources and
     * resources are separate inputs, and each has a recorded digest plus an on-disk output check so a
     * lane that was never refreshed cannot claim to be current.
     */
    private void testReasons(
            WorkspaceExecutionContext context,
            WorkspaceMember member,
            Optional<WorkspaceMemberState> previous,
            WorkspaceMemberState candidate,
            List<WorkspaceDirtyReason> reasons) {
        if (previous.isEmpty()) {
            reasons.add(WorkspaceDirtyReason.TEST_SOURCE_CHANGED);
            return;
        }
        if (!previous.orElseThrow().testCompileKey().equals(candidate.testCompileKey())) {
            reasons.add(WorkspaceDirtyReason.TEST_SOURCE_CHANGED);
        }
        // Generated-test identity includes tool versions, selected environment, glob-expanded
        // inputs, and the output tree after generation. Stage 0 cannot reproduce that canonical
        // fingerprint, so admit the test lane and let TestCompileService make the precise no-op
        // decision. This mirrors the generated-main correctness boundary above.
        if (!member.config().build().generatedTestSources().isEmpty()) {
            reasons.add(WorkspaceDirtyReason.CONSERVATIVE_GENERATED_TEST_SOURCE_STEP);
        }
        if (!previous.orElseThrow()
                .testResourceTreeDigest()
                .equals(candidate.testResourceTreeDigest())) {
            reasons.add(WorkspaceDirtyReason.TEST_RESOURCE_CHANGED);
        }
        if (observedMoved(
                previous.orElseThrow().testOutputManifestDigest(),
                candidate.testOutputManifestDigest())) {
            reasons.add(WorkspaceDirtyReason.TEST_OUTPUT_CHANGED);
        }
        if (!context.fileSnapshot()
                .testResourceOutputsCurrent(
                        member.path(), member.directory(), member.config().build())) {
            reasons.add(WorkspaceDirtyReason.TEST_RESOURCE_OUTPUT_MISSING);
        }
        Path testOutput = member.directory()
                .resolve(member.config().build().testOutput())
                .toAbsolutePath()
                .normalize();
        if (!outputsCurrent(testOutput, IncrementalCompileState.testStatePath(testOutput))
                || !fingerprintOutputs.testOutputsCurrent(member.directory(), testOutput)) {
            reasons.add(WorkspaceDirtyReason.TEST_OUTPUT_MISSING);
        }
    }

    private boolean compileOutputsCurrent(WorkspaceMember member) {
        if (member.config().packageSettings().mode() == PackageMode.BOM) {
            return true;
        }
        Path output = member.directory()
                .resolve(member.config().build().output())
                .toAbsolutePath()
                .normalize();
        return outputsCurrent(output, IncrementalCompileState.mainStatePath(output))
                && fingerprintOutputs.mainOutputsCurrent(member.directory(), output);
    }

    /** One recorded state read plus a stat per recorded class: the whole output-existence check. */
    private boolean outputsCurrent(Path outputDirectory, Path statePath) {
        Optional<IncrementalCompileState> state = compileStateCodec.read(statePath);
        return state.isPresent()
                && state.orElseThrow().outputDirectory().equals(outputDirectory)
                && state.orElseThrow().classes().stream()
                        .map(IncrementalCompileState.ClassRecord::outputPath)
                        .allMatch(Files::isRegularFile);
    }
}
