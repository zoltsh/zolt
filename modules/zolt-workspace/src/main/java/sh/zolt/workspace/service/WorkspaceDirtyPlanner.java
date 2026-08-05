package sh.zolt.workspace.service;

import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.project.PackageMode;
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
        WorkspaceState previous = stateStore.read(context.workspace().root());
        long started = System.nanoTime();
        WorkspaceMemberStateObserver observer =
                new WorkspaceMemberStateObserver(context, membersByPath);
        Set<String> processorMembers = WorkspaceCanonicalBuildPolicy.membersWithProcessorInputs(
                context.workspace(),
                context.lockfile());
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
                                    processorMembers.contains(memberPath),
                                    previous,
                                    prior,
                                    candidate)));
        }
        context.addFileSnapshotMetrics(
                Math.max(0L, System.nanoTime() - started),
                context.fileSnapshot().bytesHashed(),
                context.fileSnapshot().filesHashed());
        return new WorkspaceDirtyPlan(previous, plans);
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
        stateStore.write(context.workspace().root(), new WorkspaceState(current));
    }

    private List<WorkspaceDirtyReason> reasons(
            WorkspaceExecutionContext context,
            WorkspaceMemberStateObserver observer,
            WorkspaceMember member,
            WorkspaceBuildRequirements requirements,
            boolean hasProcessorInputs,
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
                .resourceOutputsCurrent(member.directory(), member.config().build())) {
            reasons.add(WorkspaceDirtyReason.RESOURCE_OUTPUT_MISSING);
        }
        if (hasProcessorInputs || !member.config().build().generatedMainSources().isEmpty()) {
            reasons.add(WorkspaceDirtyReason.CONSERVATIVE_GENERATED_INPUT);
        }
        if (WorkspaceCanonicalBuildPolicy.hasFrameworkOutputs(member)) {
            reasons.add(WorkspaceDirtyReason.CONSERVATIVE_FRAMEWORK_OUTPUT);
        }
        if (WorkspaceCanonicalBuildPolicy.generatesBuildMetadata(member)) {
            reasons.add(WorkspaceDirtyReason.BUILD_METADATA_REQUIRED);
        }
        if (requirements.testCompileClasspath()) {
            testReasons(member, previous, candidate, reasons);
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

    private void testReasons(
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
