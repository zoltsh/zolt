package sh.zolt.workspace.service.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceKotlinJvmPreviewPolicy;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;

final class WorkspaceKotlinJvmPreviewPolicyTest {
    @Test
    void includesDirectAndTransitiveRuntimeDependencyRequirements() {
        Workspace workspace = workspace(
                List.of(
                        member("apps/application", false),
                        member("modules/direct", false),
                        member("modules/transitive", true)),
                List.of(
                        edge("apps/application", "modules/direct", false),
                        edge("modules/direct", "modules/transitive", false)));

        assertTrue(WorkspaceKotlinJvmPreviewPolicy.mainRuntimeEnabled(
                workspace,
                "apps/application"));
        assertEquals(
                List.of("--enable-preview"),
                WorkspaceKotlinJvmPreviewPolicy.mainJvmArguments(
                        workspace,
                        "apps/application"));
    }

    @Test
    void excludesTestAndOptionalDependencyRequirementsFromMainRuntime() {
        Workspace workspace = workspace(
                List.of(
                        member("apps/application", false),
                        member("modules/test-support", true),
                        member("modules/direct", false),
                        member("modules/optional", true)),
                List.of(
                        new WorkspaceProjectEdge(
                                "apps/application",
                                "modules/test-support",
                                "test",
                                "com.example:test-support",
                                false),
                        edge("apps/application", "modules/direct", false),
                        edge("modules/direct", "modules/optional", true)));

        assertFalse(WorkspaceKotlinJvmPreviewPolicy.mainRuntimeEnabled(
                workspace,
                "apps/application"));
        assertEquals(
                List.of(),
                WorkspaceKotlinJvmPreviewPolicy.mainJvmArguments(
                        workspace,
                        "apps/application"));
    }

    @Test
    void includesTheSelectedMembersOwnRequirement() {
        Workspace workspace = workspace(
                List.of(member("apps/application", true)),
                List.of());

        assertTrue(WorkspaceKotlinJvmPreviewPolicy.mainRuntimeEnabled(
                workspace,
                "apps/application"));
    }

    private static Workspace workspace(
            List<WorkspaceMember> members,
            List<WorkspaceProjectEdge> edges) {
        return new Workspace(
                Path.of("workspace"),
                Path.of("workspace/zolt.toml"),
                null,
                members,
                edges);
    }

    private static WorkspaceMember member(String path, boolean preview) {
        String compiler = preview
                ? """

                  [compiler]
                  args = ["-Xjvm-enable-preview"]
                  """
                : "";
        return new WorkspaceMember(
                path,
                Path.of("workspace").resolve(path),
                new ManifestProjectConfigLoader().load("""
                        [project]
                        name = "%s"
                        version = "0.1.0"
                        group = "com.example"
                        java = 21
                        %s
                        """.formatted(path.replace('/', '-'), compiler)));
    }

    private static WorkspaceProjectEdge edge(
            String from,
            String to,
            boolean optional) {
        return new WorkspaceProjectEdge(
                from,
                to,
                "compile",
                "com.example:" + to.substring(to.indexOf('/') + 1),
                false,
                optional);
    }
}
