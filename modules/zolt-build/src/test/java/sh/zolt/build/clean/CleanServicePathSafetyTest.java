package sh.zolt.build.clean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.CleanException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void sharedTargetCleanPreservesSourceReachedThroughSymlinkAlias() throws IOException {
        Path source = projectDir.resolve("target/src/p/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; public final class Main {}\n");
        file("target/classes/p/Main.class");
        file("target/test-classes/p/MainTest.class");
        createSymlink(projectDir.resolve("source-link"), projectDir.resolve("target/src"));
        BuildSettings settings = new BuildSettings(
                "source-link",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes");

        cleanService.clean(projectDir, settings);

        assertTrue(Files.exists(source));
        assertTrue(Files.readString(source).contains("public final class Main"));
    }

    @Test
    void sharedTargetCleanPreservesConfiguredSourceSymlinkLocatedUnderTarget() throws IOException {
        Path source = projectDir.resolve("src/shared/p/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; public final class Main {}\n");
        Path sourceLink = projectDir.resolve("target/src-link");
        Files.createDirectories(sourceLink.getParent());
        createSymlink(sourceLink, projectDir.resolve("src/shared"));
        file("target/classes/p/Main.class");
        file("target/test-classes/p/MainTest.class");
        BuildSettings settings = new BuildSettings(
                "target/src-link",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes");

        cleanService.clean(projectDir, settings);

        assertTrue(Files.isSymbolicLink(sourceLink));
        assertTrue(Files.exists(source));
        assertTrue(Files.readString(source).contains("public final class Main"));
        assertFalse(Files.exists(projectDir.resolve("target/classes")));
        assertFalse(Files.exists(projectDir.resolve("target/test-classes")));
    }

    @Test
    void projectRootSourceStillAllowsConventionalTargetCleanup() throws IOException {
        Path source = projectDir.resolve("Main.java");
        Files.writeString(source, "public final class Main {}\n");
        file("target/classes/Main.class");
        file("target/test-classes/MainTest.class");
        BuildSettings settings = new BuildSettings(
                ".",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes");

        cleanService.clean(projectDir, settings);

        assertTrue(Files.exists(source));
        assertTrue(Files.readString(source).contains("public final class Main"));
        assertFalse(Files.exists(projectDir.resolve("target")));
    }

    @Test
    void refusesDirectCleanTargetNestedInsideSourceBeforeDeletingAnything() throws IOException {
        Path source = projectDir.resolve("target/src/p/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; public final class Main {}\n");
        file("target/src/classes/p/Main.class");
        file("target/test-classes/p/MainTest.class");
        BuildSettings settings = new BuildSettings(
                "target/src",
                "src/test/java",
                "target",
                "target/src/classes",
                "target/test-classes");

        CleanException exception = assertThrows(
                CleanException.class,
                () -> cleanService.clean(projectDir, settings));

        assertTrue(exception.getMessage().contains("Unsafe clean output layout"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
        assertTrue(Files.exists(source));
        assertTrue(Files.exists(projectDir.resolve("target/src/classes/p/Main.class")));
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/p/MainTest.class")));
    }

    @Test
    void refusesProjectLockfileAsCleanTargetBeforeDeletingAnything() throws IOException {
        Path lockfile = projectDir.resolve("zolt.lock");
        Files.writeString(lockfile, "sentinel\n");
        file("target/classes/p/Main.class");
        BuildSettings settings = BuildSettings.defaults().withGeneratedSources(
                List.of(new GeneratedSourceStep(
                        "lockfile",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "zolt.lock",
                        List.of(),
                        true,
                        true)),
                List.of());

        CleanException exception = assertThrows(
                CleanException.class,
                () -> cleanService.clean(projectDir, settings));

        assertTrue(exception.getMessage().contains("project lockfile"), exception.getMessage());
        assertTrue(Files.exists(lockfile));
        assertTrue(Files.exists(projectDir.resolve("target/classes/p/Main.class")));
    }

    @Test
    void refusesSymlinkedOutputRootThatAliasesAuthoredTreeBeforeDeletingAnything() throws IOException {
        Path source = projectDir.resolve("src/main/java/classes/p/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package p; public final class Main {}\n");
        createSymlink(projectDir.resolve("target"), projectDir.resolve("src/main/java"));
        BuildSettings settings = new BuildSettings(
                ".",
                "src/test/java",
                "target",
                "target/classes",
                "target/test-classes");

        CleanException exception = assertThrows(
                CleanException.class,
                () -> cleanService.clean(projectDir, settings));

        assertTrue(exception.getMessage().contains("Unsafe clean output layout"), exception.getMessage());
        assertTrue(exception.getMessage().contains("[build].sources[0]"), exception.getMessage());
        assertTrue(Files.exists(source));
        assertTrue(Files.readString(source).contains("public final class Main"));
    }
}
