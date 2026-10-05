package sh.zolt.workspace.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.zolt.build.BuildException;
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
        boolean enabled = KotlinJvmPreviewPolicy.mainEnabled(member.config());
        WorkspaceClasspathMemberGraph graph = new WorkspaceClasspathMemberGraph(current);
        for (String dependencyPath : graph.mainRuntime(selected)) {
            WorkspaceMember dependency = members.get(dependencyPath);
            if (dependency != null
                    && KotlinJvmPreviewPolicy.mainEnabled(dependency.config())) {
                requireSameFeature(member, dependency);
                enabled = true;
            }
        }
        return enabled;
    }

    public static List<String> testJvmArguments(
            Workspace workspace,
            String memberPath,
            List<String> configuredArguments) {
        List<String> arguments = List.copyOf(Objects.requireNonNull(
                configuredArguments,
                "Configured test JVM arguments are required."));
        if (!testRuntimeEnabled(workspace, memberPath)
                || arguments.contains(KotlinJvmPreviewPolicy.RUNTIME_ARGUMENT)) {
            return arguments;
        }
        List<String> result = new ArrayList<>(arguments);
        result.add(KotlinJvmPreviewPolicy.RUNTIME_ARGUMENT);
        return List.copyOf(result);
    }

    public static boolean testRuntimeEnabled(
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
        boolean enabled = KotlinJvmPreviewPolicy.testRuntimeEnabled(member.config());
        WorkspaceClasspathMemberGraph graph = new WorkspaceClasspathMemberGraph(current);
        for (String dependencyPath : graph.test(selected)) {
            WorkspaceMember dependency = members.get(dependencyPath);
            if (dependency != null
                    && KotlinJvmPreviewPolicy.mainEnabled(dependency.config())) {
                requireSameFeature(member, dependency);
                enabled = true;
            }
        }
        return enabled;
    }

    private static void requireSameFeature(
            WorkspaceMember consumer,
            WorkspaceMember previewDependency) {
        String consumerFeature = consumer.config().project().java();
        String dependencyFeature = previewDependency.config().project().java();
        if (consumerFeature.equals(dependencyFeature)) {
            return;
        }
        throw new BuildException(
                "Workspace member `" + consumer.path() + "` targets Java " + consumerFeature
                        + " but its preview-enabled runtime dependency `"
                        + previewDependency.path() + "` targets Java " + dependencyFeature
                        + ". JVM preview class files require the same Java feature release.\n\n"
                        + "Next: Align [project].java for these workspace members, or remove "
                        + "`-Xjvm-enable-preview` from the dependency.");
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }
}
