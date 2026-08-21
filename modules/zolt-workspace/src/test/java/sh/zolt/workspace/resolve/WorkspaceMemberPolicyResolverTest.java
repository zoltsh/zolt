package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class WorkspaceMemberPolicyResolverTest {
    private final WorkspaceMemberPolicyResolver resolver = new WorkspaceMemberPolicyResolver();

    @Test
    void mergesWorkspaceRootRepositoriesAndPlatformsIntoMemberConfig() {
        WorkspaceMember member = member("app", config(
                orderedMap("central", "https://repo.maven.apache.org/maven2"),
                Map.of()));
        Workspace workspace = new Workspace(
                Path.of("/workspace/demo"),
                Path.of("/workspace/demo/zolt.toml"),
                new WorkspaceConfig(
                        "demo",
                        List.of(member.path()),
                        List.of(),
                        orderedMap(
                                "central", "https://repo.maven.apache.org/maven2",
                                "internal", "https://repo.example/internal"),
                        orderedMap("com.example:platform", "1.0.0")),
                List.of(member));

        ProjectConfig merged = resolver.merge(workspace, member);

        assertEquals(List.of("central", "internal"), List.copyOf(merged.repositories().keySet()));
        assertEquals("https://repo.example/internal", merged.repositories().get("internal"));
        assertEquals(List.of("com.example:platform"), List.copyOf(merged.platforms().keySet()));
    }

    private static WorkspaceMember member(String path, ProjectConfig config) {
        return new WorkspaceMember(path, Path.of("/workspace/demo").resolve(path), config);
    }

    private static ProjectConfig config(Map<String, String> repositories, Map<String, String> platforms) {
        return ProjectConfigs.withRuntimeDependencySections(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.empty()),
                repositories,
                platforms,
                Map.of(),
                Set.of(),
                Map.of(),
                Set.of(),
                BuildSettings.defaults(),
                null);
    }

    private static Map<String, String> orderedMap(String... entries) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put(entries[index], entries[index + 1]);
        }
        return values;
    }
}
