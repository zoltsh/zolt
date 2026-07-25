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

    Set<String> mainCompile(String memberPath) {
        Set<String> visible = new LinkedHashSet<>();
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (isCompile(edge) && visible.add(edge.to())) {
                includeExportedCompile(edge.to(), visible);
            }
        }
        return Set.copyOf(visible);
    }

    Set<String> mainRuntime(String memberPath) {
        Set<String> visible = new LinkedHashSet<>();
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (isCompile(edge) && visible.add(edge.to())) {
                includeNonOptionalCompileDependencies(edge.to(), visible);
            }
        }
        return Set.copyOf(visible);
    }

    Set<String> test(String memberPath) {
        Set<String> visible = new LinkedHashSet<>(mainRuntime(memberPath));
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (edge.scope().equals("test") && visible.add(edge.to())) {
                includeNonOptionalCompileDependencies(edge.to(), visible);
            }
        }
        return Set.copyOf(visible);
    }

    Map<String, List<String>> compileDependenciesByMember() {
        return compileDependenciesByMember;
    }

    private void includeExportedCompile(String memberPath, Set<String> visible) {
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (isCompile(edge)
                    && edge.exported()
                    && !edge.optional()
                    && visible.add(edge.to())) {
                includeExportedCompile(edge.to(), visible);
            }
        }
    }

    private void includeNonOptionalCompileDependencies(
            String memberPath,
            Set<String> visible) {
        for (WorkspaceProjectEdge edge : edges(memberPath)) {
            if (isCompile(edge) && !edge.optional() && visible.add(edge.to())) {
                includeNonOptionalCompileDependencies(edge.to(), visible);
            }
        }
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
