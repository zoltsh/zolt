package sh.zolt.workspace.service.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.build.BuildException;
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

    @Test
    void includesTestVisibleDependencyRequirementsAndPreservesConfiguredArguments() {
        Workspace workspace = workspace(
                List.of(
                        member("apps/application", false),
                        member("modules/test-support", true)),
                List.of(new WorkspaceProjectEdge(
                        "apps/application",
                        "modules/test-support",
                        "test",
                        "com.example:test-support",
                        false)));

        assertTrue(WorkspaceKotlinJvmPreviewPolicy.testRuntimeEnabled(
                workspace,
                "apps/application"));
        assertEquals(
                List.of("-Dprobe=true", "--enable-preview"),
                WorkspaceKotlinJvmPreviewPolicy.testJvmArguments(
                        workspace,
                        "apps/application",
                        List.of("-Dprobe=true")));
        assertEquals(
                List.of("--enable-preview", "-Dprobe=true"),
                WorkspaceKotlinJvmPreviewPolicy.testJvmArguments(
                        workspace,
                        "apps/application",
                        List.of("--enable-preview", "-Dprobe=true")));
    }

    @Test
    void includesTheSelectedMembersTestOnlyRequirement() {
        Workspace workspace = workspace(
                List.of(member("apps/application", false, true)),
                List.of());

        assertTrue(WorkspaceKotlinJvmPreviewPolicy.testRuntimeEnabled(
                workspace,
                "apps/application"));
        assertFalse(WorkspaceKotlinJvmPreviewPolicy.mainRuntimeEnabled(
                workspace,
                "apps/application"));
    }

    @Test
    void rejectsPreviewDependenciesFromAnotherJavaFeature() {
        Workspace workspace = workspace(
                List.of(
                        member("apps/application", false, false, 22),
                        member("modules/library", true, false, 21)),
                List.of(
                        edge("apps/application", "modules/library", false),
                        new WorkspaceProjectEdge(
                                "apps/application",
                                "modules/library",
                                "test",
                                "com.example:library",
                                false)));

        BuildException main = assertThrows(
                BuildException.class,
                () -> WorkspaceKotlinJvmPreviewPolicy.mainRuntimeEnabled(
                        workspace,
                        "apps/application"));
        assertTrue(main.getMessage().contains(
                "preview-enabled runtime dependency `modules/library` targets Java 21"));
        assertTrue(main.getMessage().contains("require the same Java feature release"));

        BuildException test = assertThrows(
                BuildException.class,
                () -> WorkspaceKotlinJvmPreviewPolicy.testRuntimeEnabled(
                        workspace,
                        "apps/application"));
        assertTrue(test.getMessage().contains("Workspace member `apps/application` targets Java 22"));
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
        return member(path, preview, false);
    }

    private static WorkspaceMember member(
            String path,
            boolean mainPreview,
            boolean testPreview) {
        return member(path, mainPreview, testPreview, 21);
    }

    private static WorkspaceMember member(
            String path,
            boolean mainPreview,
            boolean testPreview,
            int javaFeature) {
        String compiler = mainPreview
                ? """

                  [compiler]
                  args = ["-Xjvm-enable-preview"]
                  """
                : "";
        String testCompiler = testPreview
                ? """

                  [compiler.test]
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
                        java = %s
                        %s
                        %s
                        """.formatted(
                        path.replace('/', '-'),
                        javaFeature,
                        compiler,
                        testCompiler)));
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
