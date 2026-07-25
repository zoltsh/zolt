package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.WorkspaceConfigException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceMemberSelectorTest {
    private final WorkspaceMemberSelector selector = new WorkspaceMemberSelector();

    @Test
    void selectsRequestedMembersAndWorkspaceDependenciesInBuildOrder() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core", "apps/worker"), List.of());

        WorkspaceSelection selection = selector.select(
                workspace,
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertEquals(List.of("modules/core", "apps/api"), selection.includedMembers());
        assertEquals(List.of("apps/api"), selection.selectedMembers());
    }

    @Test
    void exactSelectionNeverExpandsDependencies() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core", "apps/worker"), List.of());

        WorkspaceSelection selection = selector.select(
                workspace,
                WorkspaceSelectionRequest.exact(List.of("apps/api")));

        // Resume mode: modules/core (a dependency of apps/api) is NOT dragged back in.
        assertEquals(List.of("apps/api"), selection.includedMembers());
        assertEquals(List.of("apps/api"), selection.selectedMembers());
    }

    @Test
    void mainSelectionExcludesTestOnlyMembersButKeepsProcessors() {
        Workspace workspace = workspace(
                List.of("apps/api", "modules/core", "modules/test-support", "modules/processor"),
                List.of(),
                List.of("modules/core", "modules/test-support", "modules/processor", "apps/api"),
                List.of(
                        new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core"),
                        new WorkspaceProjectEdge(
                                "apps/api",
                                "modules/test-support",
                                "test",
                                "com.acme:test-support"),
                        new WorkspaceProjectEdge(
                                "apps/api",
                                "modules/processor",
                                "processor",
                                "com.acme:processor")));

        WorkspaceSelection main = selector.selectMain(
                workspace,
                new WorkspaceSelectionRequest(false, List.of("apps/api")));
        WorkspaceSelection tests = selector.select(
                workspace,
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertEquals(List.of("modules/core", "modules/processor", "apps/api"), main.includedMembers());
        assertEquals(
                List.of("modules/core", "modules/test-support", "modules/processor", "apps/api"),
                tests.includedMembers());
    }

    @Test
    void selectsAllMembersWhenAllIsRequested() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core", "apps/worker"), List.of());

        WorkspaceSelection selection = selector.select(
                workspace,
                new WorkspaceSelectionRequest(true, List.of()));

        assertEquals(List.of("modules/core", "apps/api", "apps/worker"), selection.includedMembers());
        assertEquals(List.of("modules/core", "apps/api", "apps/worker"), selection.selectedMembers());
    }

    @Test
    void usesDefaultMembersWhenNoExplicitSelectionIsProvided() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core", "apps/worker"), List.of("apps/api"));

        WorkspaceSelection selection = selector.select(
                workspace,
                WorkspaceSelectionRequest.defaults());

        assertEquals(List.of("modules/core", "apps/api"), selection.includedMembers());
        assertEquals(List.of("apps/api"), selection.selectedMembers());
    }

    @Test
    void normalizesAndDeduplicatesRequestedMembers() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core", "apps/worker"), List.of());

        WorkspaceSelection selection = selector.select(
                workspace,
                new WorkspaceSelectionRequest(false, List.of("apps/./api", "apps/api")));

        assertEquals(List.of("modules/core", "apps/api"), selection.includedMembers());
        assertEquals(List.of("apps/api"), selection.selectedMembers());
    }

    @Test
    void normalizesRootMemberSelection() {
        Workspace workspace = workspace(
                List.of(".", "modules/core"),
                List.of(),
                List.of("modules/core", "."),
                List.of(new WorkspaceProjectEdge(".", "modules/core", "compile", "com.acme:core")));

        WorkspaceSelection selection = selector.select(
                workspace,
                new WorkspaceSelectionRequest(false, List.of(".")));

        assertEquals(List.of("modules/core", "."), selection.includedMembers());
        assertEquals(List.of("."), selection.selectedMembers());
    }

    @Test
    void treatsNullRequestedMembersAsDefaultSelection() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core", "apps/worker"), List.of());

        WorkspaceSelection selection = selector.select(
                workspace,
                new WorkspaceSelectionRequest(false, null));

        assertEquals(List.of("modules/core", "apps/api", "apps/worker"), selection.includedMembers());
        assertEquals(List.of("modules/core", "apps/api", "apps/worker"), selection.selectedMembers());
    }

    @Test
    void selectsAllMembersWhenNoExplicitSelectionOrDefaultsExist() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core", "apps/worker"), List.of());

        WorkspaceSelection selection = selector.select(
                workspace,
                WorkspaceSelectionRequest.defaults());

        assertEquals(List.of("modules/core", "apps/api", "apps/worker"), selection.includedMembers());
        assertEquals(List.of("modules/core", "apps/api", "apps/worker"), selection.selectedMembers());
    }

    @Test
    void rejectsUnknownRequestedMembers() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core"), List.of());

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> selector.select(workspace, new WorkspaceSelectionRequest(false, List.of("apps/missing"))));

        assertEquals(
                "Workspace member `apps/missing` is not declared in [workspace].members. Choose a declared member or use --all.",
                exception.getMessage());
    }

    @Test
    void rejectsAbsoluteRequestedMembers() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core"), List.of());

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> selector.select(workspace, new WorkspaceSelectionRequest(false, List.of("/apps/api"))));

        assertEquals(
                "Invalid workspace member `/apps/api`. Use a relative member path declared in [workspace].members.",
                exception.getMessage());
    }

    @Test
    void rejectsRequestedMembersThatEscapeWorkspaceRoot() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core"), List.of());

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> selector.select(workspace, new WorkspaceSelectionRequest(false, List.of("../apps/api"))));

        assertEquals(
                "Invalid workspace member `../apps/api`. Use a relative member path declared in [workspace].members.",
                exception.getMessage());
    }

    @Test
    void rejectsBlankRequestedMembers() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core"), List.of());

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> selector.select(workspace, new WorkspaceSelectionRequest(false, List.of(" "))));

        assertEquals(
                "Invalid workspace member ` `. Use a relative member path declared in [workspace].members.",
                exception.getMessage());
    }

    @Test
    void rejectsConflictingAllAndMemberSelection() {
        Workspace workspace = workspace(List.of("apps/api", "modules/core"), List.of());

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> selector.select(workspace, new WorkspaceSelectionRequest(true, List.of("apps/api"))));

        assertEquals("Use either --all or member selection for workspace selection, not both.", exception.getMessage());
    }

    private static Workspace workspace(List<String> members, List<String> defaultMembers) {
        return workspace(
                members,
                defaultMembers,
                List.of("modules/core", "apps/api", "apps/worker").stream()
                        .filter(members::contains)
                        .toList(),
                List.of(new WorkspaceProjectEdge("apps/api", "modules/core", "compile", "com.acme:core")));
    }

    private static Workspace workspace(
            List<String> members,
            List<String> defaultMembers,
            List<String> buildOrder,
            List<WorkspaceProjectEdge> edges) {
        List<WorkspaceMember> workspaceMembers = members.stream()
                .map(member -> new WorkspaceMember(
                        member,
                        Path.of(member),
                        config(projectName(member))))
                .toList();
        return new Workspace(
                Path.of("."),
                Path.of("zolt-workspace.toml"),
                new WorkspaceConfig("workspace", members, defaultMembers, Map.of(), Map.of()),
                workspaceMembers,
                edges,
                buildOrder);
    }

    private static String projectName(String member) {
        if (".".equals(member)) {
            return "root";
        }
        return member.substring(member.lastIndexOf('/') + 1);
    }

    private static ProjectConfig config(String name) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "0.1.0", "com.acme", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }
}
