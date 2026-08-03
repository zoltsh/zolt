package sh.zolt.workspace.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes workspace-member visibility without flattening scope or API export boundaries.
 *
 * <p>Main compile sees every direct compile dependency, then only API-exported compile edges.
 * Main runtime follows every compile edge. Test sees main runtime plus each direct test dependency
 * and that dependency's main runtime closure. Processor roots remain isolated in
 * {@link WorkspaceProcessorClasspathAssembler}, which consumes only the all-compile adjacency map.
 */
final class WorkspaceClasspathMemberGraph {
    private final Map<String, List<WorkspaceProjectEdge>> edgesByMember;
    private final Map<String, List<String>> compileDependenciesByMember;
    private final Map<String, Set<String>> mainCompileClosures = new LinkedHashMap<>();
    private final Map<String, Set<String>> runtimeClosures = new LinkedHashMap<>();
    private final Map<String, Set<String>> testClosures = new LinkedHashMap<>();
    private final Map<String, Set<String>> exportedClosures = new LinkedHashMap<>();
    private final Map<String, Set<String>> nonOptionalClosures = new LinkedHashMap<>();

    WorkspaceClasspathMemberGraph(Workspace workspace) {
        Map<String, List<WorkspaceProjectEdge>> edges = new LinkedHashMap<>();
        Map<String, List<String>> compileDependencies = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            edges.put(member.path(), new ArrayList<>());
            compileDependencies.put(member.path(), new ArrayList<>());
        }
        for (WorkspaceProjectEdge edge : workspace.edges()) {
            edges.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
            if (isCompile(edge) && !edge.optional()) {
                compileDependencies
                        .computeIfAbsent(edge.from(), ignored -> new ArrayList<>())
                        .add(edge.to());
            }
        }
        edgesByMember = immutableLists(edges);
        compileDependenciesByMember = immutableLists(compileDependencies);
    }

    synchronized Set<String> mainCompile(String memberPath) {
        Set<String> cached = mainCompileClosures.get(memberPath);
        if (cached != null) {
            return cached;
        }
        Set<String> visible = new LinkedHashSet<>();
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (isCompile(edge) && visible.add(edge.to())) {
                visible.addAll(exportedCompile(edge.to()));
            }
        }
        Set<String> calculated = Set.copyOf(visible);
        mainCompileClosures.put(memberPath, calculated);
        return calculated;
    }

    synchronized Set<String> mainRuntime(String memberPath) {
        Set<String> cached = runtimeClosures.get(memberPath);
        if (cached != null) {
            return cached;
        }
        Set<String> visible = new LinkedHashSet<>();
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (isCompile(edge) && visible.add(edge.to())) {
                visible.addAll(nonOptionalCompile(edge.to()));
            }
        }
        Set<String> calculated = Set.copyOf(visible);
        runtimeClosures.put(memberPath, calculated);
        return calculated;
    }

    synchronized Set<String> test(String memberPath) {
        Set<String> cached = testClosures.get(memberPath);
        if (cached != null) {
            return cached;
        }
        Set<String> visible = new LinkedHashSet<>(mainRuntime(memberPath));
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (edge.scope().equals("test") && visible.add(edge.to())) {
                visible.addAll(nonOptionalCompile(edge.to()));
            }
        }
        Set<String> calculated = Set.copyOf(visible);
        testClosures.put(memberPath, calculated);
        return calculated;
    }

    Map<String, List<String>> compileDependenciesByMember() {
        return compileDependenciesByMember;
    }

    private Set<String> exportedCompile(String memberPath) {
        Set<String> cached = exportedClosures.get(memberPath);
        if (cached != null) {
            return cached;
        }
        Set<String> visible = new LinkedHashSet<>();
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (isCompile(edge)
                    && edge.exported()
                    && !edge.optional()
                    && visible.add(edge.to())) {
                visible.addAll(exportedCompile(edge.to()));
            }
        }
        Set<String> calculated = Set.copyOf(visible);
        exportedClosures.put(memberPath, calculated);
        return calculated;
    }

    private Set<String> nonOptionalCompile(String memberPath) {
        Set<String> cached = nonOptionalClosures.get(memberPath);
        if (cached != null) {
            return cached;
        }
        Set<String> visible = new LinkedHashSet<>();
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (isCompile(edge) && !edge.optional() && visible.add(edge.to())) {
                visible.addAll(nonOptionalCompile(edge.to()));
            }
        }
        Set<String> calculated = Set.copyOf(visible);
        nonOptionalClosures.put(memberPath, calculated);
        return calculated;
    }

    private List<WorkspaceProjectEdge> edges(String memberPath) {
        return edgesByMember.getOrDefault(memberPath, List.of());
    }

    private static boolean isCompile(WorkspaceProjectEdge edge) {
        return edge.scope().equals("compile");
    }

    private static <T> Map<String, List<T>> immutableLists(Map<String, List<T>> values) {
        Map<String, List<T>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<T>> entry : values.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }
}
