package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.BuildException;
import sh.zolt.build.CleanException;
import sh.zolt.build.ProjectMutationLock;
import sh.zolt.build.clean.CleanService;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class CompileOutputMutationLockTest {
    @TempDir
    private Path project;

    @Test
    void compileAndCleanCannotUnlinkHeldMutationLocks() throws Exception {
        try (ProjectMutationLock lease = ProjectMutationLock.acquire(project);
                ProjectMutationLock workspace = ProjectMutationLock.acquireWorkspace(project)) {
            for (String output : new String[] {
                    ".zolt", ".zolt/project-mutation.lock", ".zolt/workspace-mutation.lock"}) {
                ProjectConfig config = config(output);
                BuildException failure = assertThrows(BuildException.class,
                        () -> CompileOutputLayoutValidator.validateMain(project, config));
                assertTrue(failure.getMessage().contains("mutation lock"), failure.getMessage());
                assertThrows(CleanException.class, () -> new CleanService().clean(project, config));
                assertTrue(Files.isRegularFile(project.resolve(".zolt/project-mutation.lock")));
                assertTrue(Files.isRegularFile(project.resolve(".zolt/workspace-mutation.lock")));
            }
        }
    }

    @Test
    void compileAndCleanCannotEraseActiveRuntimeSnapshots() throws Exception {
        Path snapshot = project.resolve(".zolt/run/active/Main.class");
        Files.createDirectories(snapshot.getParent());
        Files.writeString(snapshot, "running application");
        ProjectConfig config = config(".zolt/run");
        BuildException failure = assertThrows(BuildException.class,
                () -> CompileOutputLayoutValidator.validateMain(project, config));
        assertTrue(failure.getMessage().contains("runtime snapshots"), failure.getMessage());
        assertThrows(CleanException.class, () -> new CleanService().clean(project, config));
        assertTrue(Files.isRegularFile(snapshot));
    }

    @Test
    void aSeparateOutputDirectoryUnderZoltRemainsSupported() {
        assertDoesNotThrow(() -> CompileOutputLayoutValidator.validateMain(project, config(".zolt/classes")));
    }

    private static ProjectConfig config(String mainOutput) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("layout", "1.0.0", "example", "" + Runtime.version().feature(), Optional.empty()),
                ProjectConfig.defaultRepositories(), Map.of(), Map.of(),
                new BuildSettings("src/main/java", "src/test/java", "target", mainOutput, "target/test-classes"));
    }
}
