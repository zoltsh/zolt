package sh.zolt.workspace.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.zolt.build.compile.kotlin.KotlinJvmPreviewPolicy;

/** Propagates preview-class launch requirements through workspace runtime dependencies. */
public final class WorkspaceKotlinJvmPreviewPolicy {
    private WorkspaceKotlinJvmPreviewPolicy() {
    }

    public static List<String> mainJvmArguments(
            Workspace workspace,
            String memberPath) {
        return mainRuntimeEnabled(workspace, memberPath)
                ? List.of(KotlinJvmPreviewPolicy.RUNTIME_ARGUMENT)
                : List.of();
    }

    public static boolean mainRuntimeEnabled(
            Workspace workspace,
            String memberPath) {
        Workspace current = Objects.requireNonNull(
                workspace,
                "Workspace is required.");
        String selected = Objects.requireNonNull(
                memberPath,
                "Workspace member path is required.");
        Map<String, WorkspaceMember> members = membersByPath(current);
        WorkspaceMember member = members.get(selected);
        if (member == null) {
            throw new IllegalArgumentException(
                    "Workspace member `" + selected + "` does not exist.");
        }
        if (KotlinJvmPreviewPolicy.mainEnabled(member.config())) {
            return true;
        }
        WorkspaceClasspathMemberGraph graph = new WorkspaceClasspathMemberGraph(current);
        return graph.mainRuntime(selected).stream()
                .map(members::get)
                .filter(Objects::nonNull)
                .anyMatch(dependency ->
                        KotlinJvmPreviewPolicy.mainEnabled(dependency.config()));
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }
}
