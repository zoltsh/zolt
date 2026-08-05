package sh.zolt.workspace.service;

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
        return new WorkspaceDirtyPlan(previous, withProcessorRebuilds(context, plans));
    }

    /**
     * A member whose annotation processor is itself about to be rebuilt has to be rebuilt with it.
     *
     * <p>Every other processor input is settled by comparing recorded state to what is on disk now,
     * but a workspace processor member's classes are what will emit the generated sources, and this
     * command has not compiled them yet — stage 0 is reading the previous command's output. So the
     * one thing that cannot be read is inferred instead, from the reasons stage 0 just produced for
     * the processor itself. A processor rebuild that turns out to emit identical bytes costs the
     * consumer a pipeline trip and no compilation, because its own fingerprint still matches.
     */
    private static Map<String, WorkspaceDirtyPlan.MemberPlan> withProcessorRebuilds(
            WorkspaceExecutionContext context,
            Map<String, WorkspaceDirtyPlan.MemberPlan> plans) {
        Map<String, WorkspaceDirtyPlan.MemberPlan> propagated = new LinkedHashMap<>();
        plans.forEach((memberPath, memberPlan) -> propagated.put(
                memberPath,
                rebuildsAProcessorOf(context, plans, memberPath)
                        ? memberPlan.with(WorkspaceDirtyReason.PROCESSOR_INPUT_CHANGED)
                        : memberPlan));
        return propagated;
    }

    private static boolean rebuildsAProcessorOf(
            WorkspaceExecutionContext context,
            Map<String, WorkspaceDirtyPlan.MemberPlan> plans,
            String memberPath) {
        for (String processor : WorkspaceCanonicalBuildPolicy.processorMembers(
                context.workspace(),
                memberPath,
                context.memberGraph().compileDependenciesByMember())) {
            WorkspaceDirtyPlan.MemberPlan processorPlan = plans.get(processor);
            if (processorPlan != null && processorPlan.buildRequired()) {
                return true;
            }
        }
        return false;
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
            Set<String> executedMembers) {
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
                current.put(memberPath, plan.member(memberPath).candidateState());
                continue;
            }
            context.fileSnapshot().forget(memberPath, WorkspaceFileKind.GENERATED_OUTPUT);
            context.abiIndex().refreshMain(
                    member.directory().resolve(member.config().build().output()));
            if (requirements.testCompileClasspath()) {
                context.abiIndex().refreshTest(
                        member.directory().resolve(member.config().build().testOutput()));
            }
            current.put(
                    memberPath,
                    observer.observe(
                            member,
                            requirements,
                            toolchainIdentitiesByMember.getOrDefault(memberPath, ""),
                            plan.previousState().member(memberPath)));
        }
        stateStore.write(
                context.workspace().root(),
                new WorkspaceState(current, context.fileSnapshot().state()));
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
        if (!previous.orElseThrow()
                .testResourceTreeDigest()
                .equals(candidate.testResourceTreeDigest())) {
            reasons.add(WorkspaceDirtyReason.TEST_RESOURCE_CHANGED);
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
        if (!outputsCurrent(testOutput, IncrementalCompileState.testStatePath(testOutput))) {
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
        return outputsCurrent(output, IncrementalCompileState.mainStatePath(output));
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
