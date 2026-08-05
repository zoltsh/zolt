package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Turns the member graph into a schedule. Ready members are ordered by the work that still depends on
 * them — longest remaining dependency path first — because the member at the head of the critical
 * path is the one every later wave is waiting for. A hub like {@code platform} therefore starts in the
 * first instant rather than after the members that happen to be declared before it, and the rest of
 * the wave packs behind it largest-first so the tail does not end on one long straggler.
 */
final class WorkspaceBuildBatchPlanner {
    List<List<String>> batches(Workspace workspace, List<String> includedMembers) {
        Plan plan = plan(workspace, includedMembers);
        Map<String, Integer> remaining = new LinkedHashMap<>(plan.dependencyCounts());
        PriorityQueue<String> ready = plan.readyMembers();
        List<List<String>> batches = new ArrayList<>();
        while (!ready.isEmpty()) {
            List<String> batch = drain(ready);
            batches.add(List.copyOf(batch));
            unlockDependents(batch, remaining, plan.dependentsByDependency(), ready);
        }
        return List.copyOf(batches);
    }

    Plan plan(Workspace workspace, List<String> includedMembers) {
        return plan(workspace, includedMembers, member -> 0);
    }

    Plan plan(Workspace workspace, List<String> includedMembers, ToIntFunction<String> estimatedCost) {
        Set<String> included = new LinkedHashSet<>(includedMembers);
        Map<String, Integer> memberOrder = memberOrder(includedMembers);
        Map<String, Integer> dependencyCounts = new LinkedHashMap<>();
        Map<String, List<String>> dependentsByDependency = new LinkedHashMap<>();
        for (String member : includedMembers) {
            dependencyCounts.put(member, 0);
            dependentsByDependency.put(member, new ArrayList<>());
        }
        for (WorkspaceProjectEdge edge : workspace.edges()) {
            if (included.contains(edge.from()) && included.contains(edge.to())) {
                dependencyCounts.put(edge.from(), dependencyCounts.get(edge.from()) + 1);
                dependentsByDependency.get(edge.to()).add(edge.from());
            }
        }
        Topology topology = validateAndMeasure(includedMembers, dependencyCounts, dependentsByDependency);
        Map<String, Integer> costs = new LinkedHashMap<>();
        includedMembers.forEach(member -> costs.put(member, Math.max(0, estimatedCost.applyAsInt(member))));
        return new Plan(
                includedMembers,
                dependencyCounts,
                dependentsByDependency,
                memberOrder,
                criticalPathLengths(topology.order(), dependentsByDependency),
                costs,
                topology.depth());
    }

    /**
     * How much work still waits on each member: its own step plus the longest chain of dependents
     * beneath it. Computed by walking the topological order backwards, so each member is measured
     * after everything that depends on it.
     */
    private static Map<String, Integer> criticalPathLengths(
            List<String> topologicalOrder,
            Map<String, List<String>> dependentsByDependency) {
        Map<String, Integer> lengths = new LinkedHashMap<>();
        for (int index = topologicalOrder.size() - 1; index >= 0; index--) {
            String member = topologicalOrder.get(index);
            int longest = 0;
            for (String dependent : dependentsByDependency.get(member)) {
                longest = Math.max(longest, lengths.getOrDefault(dependent, 0));
            }
            lengths.put(member, longest + 1);
        }
        return lengths;
    }

    private static Topology validateAndMeasure(
            List<String> includedMembers,
            Map<String, Integer> dependencyCounts,
            Map<String, List<String>> dependentsByDependency) {
        Map<String, Integer> remaining = new LinkedHashMap<>(dependencyCounts);
        Map<String, Integer> depths = new LinkedHashMap<>();
        includedMembers.forEach(member -> depths.put(member, 1));
        ArrayDeque<String> ready = new ArrayDeque<>();
        remaining.forEach((member, count) -> {
            if (count == 0) {
                ready.addLast(member);
            }
        });
        List<String> order = new ArrayList<>(includedMembers.size());
        while (!ready.isEmpty()) {
            String member = ready.removeFirst();
            order.add(member);
            for (String dependent : dependentsByDependency.get(member)) {
                depths.put(dependent, Math.max(depths.get(dependent), depths.get(member) + 1));
                int count = remaining.get(dependent) - 1;
                remaining.put(dependent, count);
                if (count == 0) {
                    ready.addLast(dependent);
                }
            }
        }
        if (order.size() != includedMembers.size()) {
            throw new BuildException("Workspace build graph contains a dependency cycle among selected members.");
        }
        return new Topology(order, depths.values().stream().mapToInt(Integer::intValue).max().orElse(0));
    }

    private record Topology(List<String> order, int depth) {
    }

    private static void unlockDependents(
            List<String> completed,
            Map<String, Integer> remaining,
            Map<String, List<String>> dependentsByDependency,
            PriorityQueue<String> ready) {
        for (String member : completed) {
            for (String dependent : dependentsByDependency.get(member)) {
                int count = remaining.get(dependent) - 1;
                remaining.put(dependent, count);
                if (count == 0) {
                    ready.add(dependent);
                }
            }
        }
    }

    private static List<String> drain(PriorityQueue<String> ready) {
        List<String> members = new ArrayList<>();
        while (!ready.isEmpty()) {
            members.add(ready.remove());
        }
        return members;
    }

    private static PriorityQueue<String> readyQueue(
            Map<String, Integer> memberOrder,
            Map<String, Integer> criticalPathLengths,
            Map<String, Integer> estimatedCosts) {
        Comparator<String> order = Comparator
                .<String>comparingInt(member -> -criticalPathLengths.getOrDefault(member, 1))
                .thenComparingInt(member -> -estimatedCosts.getOrDefault(member, 0))
                .thenComparingInt(member -> memberOrder.getOrDefault(member, 0));
        return new PriorityQueue<>(order);
    }

    private static Map<String, Integer> memberOrder(List<String> members) {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < members.size(); index++) {
            order.put(members.get(index), index);
        }
        return order;
    }

    record Plan(
            List<String> includedMembers,
            Map<String, Integer> dependencyCounts,
            Map<String, List<String>> dependentsByDependency,
            Map<String, Integer> memberOrder,
            Map<String, Integer> criticalPathLengths,
            Map<String, Integer> estimatedCosts,
            int dependencyDepth) {
        Plan {
            includedMembers = List.copyOf(includedMembers);
            dependencyCounts = Map.copyOf(dependencyCounts);
            Map<String, List<String>> copiedDependents = new LinkedHashMap<>();
            dependentsByDependency.forEach(
                    (member, dependents) -> copiedDependents.put(member, List.copyOf(dependents)));
            dependentsByDependency = Map.copyOf(copiedDependents);
            memberOrder = Map.copyOf(memberOrder);
            criticalPathLengths = Map.copyOf(criticalPathLengths);
            estimatedCosts = Map.copyOf(estimatedCosts);
        }

        PriorityQueue<String> readyMembers() {
            PriorityQueue<String> ready = readyQueue(memberOrder, criticalPathLengths, estimatedCosts);
            dependencyCounts.forEach((member, count) -> {
                if (count == 0) {
                    ready.add(member);
                }
            });
            return ready;
        }
    }
}
