package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

final class TestCompileServiceIncrementalAbiTest {
    private final TestCompileService testCompileService = new TestCompileService();

    @TempDir
    private Path projectDir;

    @Test
    void publicAbiChangeRecompilesTestSourcesThatReferenceChangedClass() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        Path apiSource = source("src/test/java/com/example/testapi/TestApi.java", """
                package com.example.testapi;

                public final class TestApi {
                    public static String message() {
                        return "one";
                    }
                }
                """);
        source("src/test/java/com/example/app/UseTestApi.java", """
                package com.example.app;

                import com.example.testapi.TestApi;

                public final class UseTestApi {
                    public String message() {
                        return TestApi.message();
                    }
                }
                """);
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(apiSource, """
                package com.example.testapi;

                public final class TestApi {
                    public static String message() {
                        return "two";
                    }

                    public static String extra() {
                        return "extra";
                    }
                }
                """);

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.testCompilationMode());
        assertEquals("", result.testIncrementalFallbackReason());
        assertEquals(2, result.sourceCount());
    }

    @Test
    void privateOnlyTestSourceChangeDoesNotRecompileDependents() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        Path apiSource = source("src/test/java/com/example/testapi/TestApi.java", """
                package com.example.testapi;

                public final class TestApi {
                    public static String message() {
                        return hidden();
                    }

                    private static String hidden() {
                        return "one";
                    }
                }
                """);
        source("src/test/java/com/example/app/UseTestApi.java", """
                package com.example.app;

                import com.example.testapi.TestApi;

                public final class UseTestApi {
                    public String message() {
                        return TestApi.message();
                    }
                }
                """);
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(apiSource, """
                package com.example.testapi;

                public final class TestApi {
                    public static String message() {
                        return hidden();
                    }

                    private static String hidden() {
                        return "two";
                    }
                }
                """);

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.testCompilationMode());
        assertEquals("", result.testIncrementalFallbackReason());
        assertEquals(1, result.sourceCount());
    }

    @Test
    void packagePrivateAbiChangeRecompilesSamePackageTestSources() throws IOException {
        writeLockfile("version = 7\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        Path apiSource = source("src/test/java/com/example/TestApi.java", """
                package com.example;

                public final class TestApi {
                    static String packageMessage() {
                        return "one";
                    }
                }
                """);
        source("src/test/java/com/example/NeighborTest.java", """
                package com.example;

                public final class NeighborTest {
                    public String message() {
                        return "neighbor";
                    }
                }
                """);
        testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));
        Files.writeString(apiSource, """
                package com.example;

                public final class TestApi {
                    static int packageMessage() {
                        return 2;
                    }
                }
                """);

        TestCompileResult result = testCompileService.compileTests(projectDir, config(), projectDir.resolve("cache"));

        assertEquals("incremental", result.testCompilationMode());
        assertEquals("", result.testIncrementalFallbackReason());
        assertEquals(2, result.sourceCount());
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", currentJavaMajorVersion(), Optional.of("com.example.Main")),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private Path source(String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private void writeLockfile(String content) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
