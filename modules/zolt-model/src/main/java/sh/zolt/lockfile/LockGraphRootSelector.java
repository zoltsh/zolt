package sh.zolt.lockfile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selects the persisted graph roots without consulting the legacy {@code direct} projection. */
public final class LockGraphRootSelector {
    private LockGraphRootSelector() {
    }

    /**
     * Selects one member's roots out of its slice of an aggregated workspace lock.
     *
     * <p>A member slice is not edge-closed by construction: the aggregate attributes a workspace
     * sibling's own externals to the sibling rather than to the consuming member, while the sibling's
     * lock entry still carries edges into them. Selecting over the bare slice would report those edges
     * as dangling even though the aggregated lock is intact, so the slice is first closed over
     * {@code aggregatePackages}. Carried-in packages always gain an incoming edge and therefore never
     * become injected roots of this member, while a member package no authored root reaches still
     * contributes its own traversal root. Member views win over their aggregate entry by exact edge ref.
     */
    public static List<LockPackage> select(
            List<LockPackage> memberPackages,
            List<LockDependencyRoot> dependencyRoots,
            List<LockPackage> aggregatePackages,
            String regenerateCommand) {
        Map<String, LockPackage> universe = new LinkedHashMap<>();
        aggregatePackages.forEach(lockPackage -> universe.putIfAbsent(ref(lockPackage), lockPackage));
        memberPackages.forEach(lockPackage -> universe.put(ref(lockPackage), lockPackage));
        LockDependencyIndex index = new LockDependencyIndex(universe.values());

        Map<String, LockPackage> closure = new LinkedHashMap<>();
        Deque<LockPackage> pending = new ArrayDeque<>(memberPackages);
        while (!pending.isEmpty()) {
            LockPackage current = pending.removeFirst();
            if (closure.putIfAbsent(ref(current), current) != null) {
                continue;
            }
            for (String edge : current.dependencies()) {
                index.resolveGraphEdge(edge, regenerateCommand)
                        .filter(target -> !closure.containsKey(ref(target)))
                        .ifPresent(pending::addLast);
            }
        }
        return select(List.copyOf(closure.values()), dependencyRoots, regenerateCommand);
    }

    /**
     * Returns every exact package selected by an authored root plus one deterministic representative
     * from each otherwise-unrooted source component. The latter represents resolver-injected traversal
     * roots, including a disconnected injected cycle whose members all have incoming edges.
     */
    public static List<LockPackage> select(
            List<LockPackage> packages,
            List<LockDependencyRoot> dependencyRoots,
            String regenerateCommand) {
        Map<String, LockPackage> byRef = new LinkedHashMap<>();
        packages.forEach(lockPackage -> byRef.putIfAbsent(ref(lockPackage), lockPackage));
        LockDependencyIndex index = new LockDependencyIndex(packages);
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (Map.Entry<String, LockPackage> entry : byRef.entrySet()) {
            Set<String> targets = new LinkedHashSet<>();
            for (String edge : entry.getValue().dependencies()) {
                targets.add(ref(index.resolveGraphEdge(edge, regenerateCommand).orElseThrow()));
            }
            graph.put(entry.getKey(), targets);
        }

        Set<String> authored = new LinkedHashSet<>();
        for (LockDependencyRoot root : dependencyRoots) {
            if (root.publishOnly()) {
                continue;
            }
            List<String> selected = byRef.entrySet().stream()
                    .filter(entry -> root.selects(entry.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            if (selected.size() != 1) {
                throw new LockDependencyGraphException(
                        "Dependency root `" + description(root) + "` selects " + selected.size()
                                + " packages in the projected lock graph. Run `" + regenerateCommand
                                + "` to regenerate zolt.lock.");
            }
            authored.add(selected.getFirst());
        }

        Components components = Components.of(graph);
        boolean[] incoming = new boolean[components.count()];
        boolean[] authoredComponent = new boolean[components.count()];
        authored.forEach(key -> authoredComponent[components.componentOf(key)] = true);
        graph.forEach((source, targets) -> targets.forEach(target -> {
            int sourceComponent = components.componentOf(source);
            int targetComponent = components.componentOf(target);
            if (sourceComponent != targetComponent) {
                incoming[targetComponent] = true;
            }
        }));

        Set<String> selected = new LinkedHashSet<>(authored);
        for (int component = 0; component < components.count(); component++) {
            if (!incoming[component] && !authoredComponent[component]) {
                selected.add(components.canonicalMember(component));
            }
        }
        return selected.stream()
                .sorted()
                .map(byRef::get)
                .toList();
    }

    private static String ref(LockPackage lockPackage) {
        return LockDependencyEdge.of(lockPackage).encode();
    }

    private static String description(LockDependencyRoot root) {
        return root.member() + ":" + root.lane().name().toLowerCase().replace('_', '-') + ":"
                + root.packageId() + ":" + root.version() + ":" + root.variant().key() + ":"
                + root.resolvedScope().orElseThrow().lockfileName();
    }

    private static final class Components {
        private final Map<String, Set<String>> graph;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final List<String> stack = new ArrayList<>();
        private final Set<String> onStack = new HashSet<>();
        private final Map<String, Integer> componentByMember = new HashMap<>();
        private final List<List<String>> members = new ArrayList<>();
        private int nextIndex;

        private Components(Map<String, Set<String>> graph) {
            this.graph = graph;
        }

        static Components of(Map<String, Set<String>> graph) {
            Components components = new Components(graph);
            graph.keySet().forEach(member -> {
                if (!components.index.containsKey(member)) {
                    components.visit(member);
                }
            });
            return components;
        }

        int count() {
            return members.size();
        }

        int componentOf(String member) {
            return componentByMember.get(member);
        }

        String canonicalMember(int component) {
            return members.get(component).stream().min(Comparator.naturalOrder()).orElseThrow();
        }

        private void visit(String member) {
            index.put(member, nextIndex);
            lowLink.put(member, nextIndex++);
            stack.add(member);
            onStack.add(member);
            for (String target : graph.getOrDefault(member, Set.of())) {
                if (!index.containsKey(target)) {
                    visit(target);
                    lowLink.put(member, Math.min(lowLink.get(member), lowLink.get(target)));
                } else if (onStack.contains(target)) {
                    lowLink.put(member, Math.min(lowLink.get(member), index.get(target)));
                }
            }
            if (lowLink.get(member).equals(index.get(member))) {
                List<String> componentMembers = new ArrayList<>();
                String popped;
                do {
                    popped = stack.removeLast();
                    onStack.remove(popped);
                    componentByMember.put(popped, members.size());
                    componentMembers.add(popped);
                } while (!popped.equals(member));
                members.add(List.copyOf(componentMembers));
            }
        }
    }
}
