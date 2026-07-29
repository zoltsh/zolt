package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

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
        int depth = validateAndMeasureDepth(
                includedMembers,
                dependencyCounts,
                dependentsByDependency,
                memberOrder);
        return new Plan(
                includedMembers,
                dependencyCounts,
                dependentsByDependency,
                memberOrder,
                depth);
    }

    private static int validateAndMeasureDepth(
            List<String> includedMembers,
            Map<String, Integer> dependencyCounts,
            Map<String, List<String>> dependentsByDependency,
            Map<String, Integer> memberOrder) {
        Map<String, Integer> remaining = new LinkedHashMap<>(dependencyCounts);
        Map<String, Integer> depths = new LinkedHashMap<>();
        includedMembers.forEach(member -> depths.put(member, 1));
        PriorityQueue<String> ready = readyQueue(memberOrder);
        remaining.forEach((member, count) -> {
            if (count == 0) {
                ready.add(member);
            }
        });
        int planned = 0;
        while (!ready.isEmpty()) {
            String member = ready.remove();
            planned++;
            for (String dependent : dependentsByDependency.get(member)) {
                depths.put(dependent, Math.max(depths.get(dependent), depths.get(member) + 1));
                int count = remaining.get(dependent) - 1;
                remaining.put(dependent, count);
                if (count == 0) {
                    ready.add(dependent);
                }
            }
        }
        if (planned != includedMembers.size()) {
            throw new BuildException("Workspace build graph contains a dependency cycle among selected members.");
        }
        return depths.values().stream().mapToInt(Integer::intValue).max().orElse(0);
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

    private static PriorityQueue<String> readyQueue(Map<String, Integer> memberOrder) {
        return new PriorityQueue<>(
                (left, right) -> Integer.compare(memberOrder.get(left), memberOrder.get(right)));
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
            int dependencyDepth) {
        Plan {
            includedMembers = List.copyOf(includedMembers);
            dependencyCounts = Map.copyOf(dependencyCounts);
            Map<String, List<String>> copiedDependents = new LinkedHashMap<>();
            dependentsByDependency.forEach(
                    (member, dependents) -> copiedDependents.put(member, List.copyOf(dependents)));
            dependentsByDependency = Map.copyOf(copiedDependents);
            memberOrder = Map.copyOf(memberOrder);
        }

        PriorityQueue<String> readyMembers() {
            PriorityQueue<String> ready = readyQueue(memberOrder);
            dependencyCounts.forEach((member, count) -> {
                if (count == 0) {
                    ready.add(member);
                }
            });
            return ready;
        }
    }
}
