package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TestCompileServiceConservativeCompilationTest {
    private final TestCompileService testCompileService = new TestCompileService();

    @TempDir
    private Path projectDir;

    @Test
    void changedTestSourceRecompilesTheWholeTestScopeByDefault() throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        source("src/main/java/p/Main.java", "package p; public final class Main {}\n");
        Path changed = source("src/test/java/p/ChangedTest.java", """
                package p;

                public final class ChangedTest {
                    String message() {
                        return "before";
                    }
                }
                """);
        source("src/test/java/p/UnchangedTest.java", "package p; public final class UnchangedTest {}\n");
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(changed, """
                package p;

                public final class ChangedTest {
                    String message() {
                        return "after";
                    }
                }
                """);

        TestCompileResult result =
                testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertTrue(result.buildResult().mainCompilationSkipped());
        assertFalse(result.testCompilationSkipped());
        assertEquals("full", result.testCompilationMode());
        assertEquals("source-changed", result.testIncrementalFallbackReason());
        assertEquals(2, result.sourceCount());
        assertEquals(1, result.testCompileDiagnostics().sourcesChanged());
        assertEquals(2, result.testCompileDiagnostics().sourcesRecompiled());
    }

    @Test
    void preservedTimestampTestEditAndMissingNestedOutputRecompile() throws Exception {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n");
        source("src/main/java/p/Main.java", "package p; public class Main {}\n");
        Path test = source("src/test/java/p/ChangedTest.java", """
                package p;
                public class ChangedTest {
                    public static int value() { return 1; }
                    static class Nested {}
                }
                """);
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        var originalTime = Files.getLastModifiedTime(test);
        Files.writeString(test, Files.readString(test).replace("return 1", "return 2"));
        Files.setLastModifiedTime(test, originalTime);

        TestCompileResult edited = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertTrue(edited.buildResult().mainCompilationSkipped());
        assertFalse(edited.testCompilationSkipped());
        try (var classes = new java.net.URLClassLoader(
                new java.net.URL[] {projectDir.resolve("target/test-classes").toUri().toURL()}, null)) {
            assertEquals(2, classes.loadClass("p.ChangedTest").getMethod("value").invoke(null));
        }
        Path nested = projectDir.resolve("target/test-classes/p/ChangedTest$Nested.class");
        Files.delete(nested);
        TestCompileResult repaired = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        assertFalse(repaired.testCompilationSkipped());
        assertTrue(Files.isRegularFile(nested));
    }

    private Path source(String relative, String content) throws IOException {
        Path source = projectDir.resolve(relative);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "p",
                        currentJavaMajorVersion(),
                        Optional.of("p.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
