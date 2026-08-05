package sh.zolt.workspace.testpool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceTestScheduleTest {
    @TempDir
    Path tempDir;

    @Test
    void submitsTheHeaviestMembersFirst() {
        List<String> members = List.of("a", "b", "c", "d");
        Map<String, Integer> weights = Map.of("a", 1, "b", 9, "c", 4, "d", 0);

        assertEquals(List.of(1, 2, 0, 3), WorkspaceTestSchedule.order(members, weights));
    }

    @Test
    void keepsSelectionOrderWhenWeightsTie() {
        List<String> members = List.of("a", "b", "c");
        Map<String, Integer> weights = Map.of("a", 5, "b", 5, "c", 5);

        assertEquals(List.of(0, 1, 2), WorkspaceTestSchedule.order(members, weights));
    }

    @Test
    void treatsUnknownMembersAsWeightless() {
        List<String> members = List.of("a", "b");
        Map<String, Integer> weights = Map.of("b", 3);

        assertEquals(List.of(1, 0), WorkspaceTestSchedule.order(members, weights));
    }

    @Test
    void schedulesEveryMemberExactlyOnce() {
        List<String> members = List.of("a", "b", "c", "d", "e");
        Map<String, Integer> weights = Map.of("a", 2, "b", 2, "c", 7, "d", 0, "e", 7);

        List<Integer> order = WorkspaceTestSchedule.order(members, weights);

        assertEquals(members.size(), order.size());
        assertEquals(List.of(0, 1, 2, 3, 4), order.stream().sorted().toList());
        assertEquals(List.of(2, 4, 0, 1, 3), order);
    }

    @Test
    void emptySelectionSchedulesNothing() {
        assertEquals(List.of(), WorkspaceTestSchedule.order(List.of(), Map.of()));
    }

    @Test
    void weighsMembersByTheirTestSourceCount() throws IOException {
        WorkspaceMember small = member("small", 1);
        WorkspaceMember large = member("large", 3);
        List<String> members = List.of("small", "large");

        Map<String, Integer> weights = WorkspaceTestSchedule.testSourceWeights(
                members,
                Map.of("small", small, "large", large));

        assertEquals(1, weights.get("small"));
        assertEquals(3, weights.get("large"));
        assertEquals(List.of(1, 0), WorkspaceTestSchedule.order(members, weights));
    }

    @Test
    void weighsMembersWithoutTestSourcesAsZero() throws IOException {
        WorkspaceMember bare = member("bare", 0);

        Map<String, Integer> weights =
                WorkspaceTestSchedule.testSourceWeights(List.of("bare"), Map.of("bare", bare));

        assertEquals(0, weights.get("bare"));
    }

    private WorkspaceMember member(String name, int testSources) throws IOException {
        Path directory = tempDir.resolve(name);
        Path testRoot = directory.resolve("src/test/java/example");
        if (testSources > 0) {
            Files.createDirectories(testRoot);
            for (int index = 0; index < testSources; index++) {
                Files.writeString(
                        testRoot.resolve("Sample" + index + "Test.java"),
                        "package example;\nclass Sample" + index + "Test {}\n");
            }
        } else {
            Files.createDirectories(directory);
        }
        return new WorkspaceMember(name, directory, config(name));
    }

    private static ProjectConfig config(String name) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "0.1.0", "com.example", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }
}
