package sh.zolt.build.clean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.CleanException;
import sh.zolt.project.BuildSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CleanServicePathSafetyTest extends CleanServiceTestSupport {
    @Test
    void refusesOutputPathOutsideProject() {
        CleanException exception = assertThrows(
                CleanException.class,
                () -> cleanService.clean(
                        projectDir,
                        new BuildSettings("src/main/java", "src/test/java", "../outside", "target/test-classes")));

        assertTrue(exception.getMessage().contains("[build.output].main"));
        assertTrue(exception.getMessage().contains("../outside"));
    }

    @Test
    void refusesWindowsStyleOutputPath() {
        CleanException exception = assertThrows(
                CleanException.class,
                () -> cleanService.clean(
                        projectDir,
                        new BuildSettings("src/main/java", "src/test/java", "C:\\outside\\classes", "target/test-classes")));

        assertTrue(exception.getMessage().contains("[build.output].main"));
        assertTrue(exception.getMessage().contains("C:\\outside\\classes"));
    }

    @Test
    void refusesOutputSymlinkThatEscapesProject() throws IOException {
        Path outside = Files.createTempDirectory(projectDir.getParent(), "outside-clean-");
        createSymlink(projectDir.resolve("target/classes"), outside);

        CleanException exception = assertThrows(
                CleanException.class,
                () -> cleanService.clean(projectDir, BuildSettings.defaults()));

        assertTrue(exception.getMessage().contains("[build.output].main"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
        assertTrue(Files.exists(outside));
    }

    @Test
    void refusesOutputWithSymlinkedParentEvenWhenOutputIsMissing() throws IOException {
        Path outside = Files.createTempDirectory(projectDir.getParent(), "outside-clean-parent-");
        createSymlink(projectDir.resolve("target"), outside);

        CleanException exception = assertThrows(
                CleanException.class,
                () -> cleanService.clean(projectDir, BuildSettings.defaults()));

        assertTrue(exception.getMessage().contains("[build.output].main"));
        assertTrue(exception.getMessage().contains("target/classes"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
        assertTrue(Files.exists(outside));
    }
}
