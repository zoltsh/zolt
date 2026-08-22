package sh.zolt.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces that the workspace module dependency graph -- the {@code "sh.zolt:<module>" = { workspace =
 * true }} edges declared across every module's zolt.toml -- is a directed acyclic graph that references
 * only modules that exist. {@code WorkspaceBuildOrderPlanner} already rejects cyclic workspace deps when
 * it computes a build order at runtime; this lifts that DAG into an explicit, enforced architectural
 * invariant so a cyclic or dangling workspace dependency fails fast in the arch suite (the way the
 * originally-blocked  cycle would have).
 *
 * <p>A final-language workspace edge carries no member path (design §9.8), so the provider is derived
 * from the artifact half of its {@code sh.zolt:<module>} coordinate: every provider in this repository
 * is a {@code modules/<artifact>} member.
 */
final class WorkspaceModuleGraphTest {
    private static final Pattern WORKSPACE_EDGE =
            Pattern.compile("\"sh\\.zolt:([\\w-]+)\"\\s*=\\s*\\{[^}]*workspace\\s*=\\s*true");

    @Test
    void workspaceModuleDependencyGraphIsAcyclic() throws IOException {
        Map<String, List<String>> graph = readWorkspaceGraph();
        Optional<List<String>> cycle = findCycle(graph);
        assertTrue(
                cycle.isEmpty(),
                () -> "Workspace module dependency cycle detected: " + String.join(" -> ", cycle.orElseThrow()));
    }

    @Test
    void everyDeclaredWorkspaceDependencyReferencesAnExistingModule() throws IOException {
        Map<String, List<String>> graph = readWorkspaceGraph();
        List<String> dangling = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : graph.entrySet()) {
            for (String dependency : entry.getValue()) {
                if (!graph.containsKey(dependency)) {
                    dangling.add(entry.getKey() + " -> " + dependency);
                }
            }
        }
        assertEquals(
                List.of(),
                dangling,
                "Each workspace dependency must reference an existing module that owns a zolt.toml.");
    }

    @Test
    void cycleDetectorFlagsASyntheticCycle() {
        Map<String, List<String>> cyclic = new LinkedHashMap<>();
        cyclic.put("modules/a", List.of("modules/b"));
        cyclic.put("modules/b", List.of("modules/c"));
        cyclic.put("modules/c", List.of("modules/a"));
        assertTrue(findCycle(cyclic).isPresent(), "Detector must flag a -> b -> c -> a.");
    }

    @Test
    void cycleDetectorAcceptsACleanDag() {
        Map<String, List<String>> dag = new LinkedHashMap<>();
        dag.put("modules/model", List.of());
        dag.put("modules/test-model", List.of("modules/model"));
        dag.put("modules/junit", List.of("modules/model", "modules/test-model"));
        assertTrue(findCycle(dag).isEmpty(), "A clean DAG must not be flagged.");
    }

    private static Map<String, List<String>> readWorkspaceGraph() throws IOException {
        Map<String, List<String>> graph = new TreeMap<>();
        Path root = RepositoryPaths.root();
        List<Path> manifests = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root.resolve("modules"))) {
            modules.filter(Files::isDirectory)
                    .map(directory -> directory.resolve("zolt.toml"))
                    .filter(Files::isRegularFile)
                    .forEach(manifests::add);
        }
        Path appManifest = root.resolve("apps/zolt/zolt.toml");
        if (Files.isRegularFile(appManifest)) {
            manifests.add(appManifest);
        }
        for (Path manifest : manifests) {
            String module = root.relativize(manifest.getParent()).toString().replace('\\', '/');
            List<String> dependencies = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            Matcher matcher = WORKSPACE_EDGE.matcher(Files.readString(manifest));
            while (matcher.find()) {
                String dependency = "modules/" + matcher.group(1);
                if (!dependency.equals(module) && seen.add(dependency)) {
                    dependencies.add(dependency);
                }
            }
            graph.put(module, dependencies);
        }
        return graph;
    }

    private static Optional<List<String>> findCycle(Map<String, List<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        List<String> stack = new ArrayList<>();
        for (String node : graph.keySet()) {
            Optional<List<String>> cycle = visit(node, graph, visited, onStack, stack);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> visit(
            String node,
            Map<String, List<String>> graph,
            Set<String> visited,
            Set<String> onStack,
            List<String> stack) {
        if (onStack.contains(node)) {
            List<String> cycle = new ArrayList<>(stack.subList(stack.indexOf(node), stack.size()));
            cycle.add(node);
            return Optional.of(cycle);
        }
        if (!visited.add(node)) {
            return Optional.empty();
        }
        onStack.add(node);
        stack.add(node);
        for (String next : graph.getOrDefault(node, List.of())) {
            Optional<List<String>> cycle = visit(next, graph, visited, onStack, stack);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        onStack.remove(node);
        stack.remove(stack.size() - 1);
        return Optional.empty();
    }
}
