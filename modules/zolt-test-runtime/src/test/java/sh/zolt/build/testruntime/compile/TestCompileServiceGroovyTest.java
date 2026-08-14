package sh.zolt.build.testruntime.compile;

import static sh.zolt.build.TestContentAddressedLockSupport.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.BuildService;
import sh.zolt.build.compile.GroovyCompilerRunner;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.resources.ResourceCopier;
import sh.zolt.build.discovery.SourceDiscoverer;
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

final class TestCompileServiceGroovyTest extends TestCompileServiceGroovyTestSupport {
    private final TestCompileService testCompileService = new TestCompileService();

    @TempDir
    private Path projectDir;

    @Test
    void compilesGroovyTestSourcesAfterJavaTestSources() throws IOException {
        writeLockfile(projectDir, "version = 1\n");
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return "main";
                    }
                }
                """);
        source(projectDir, "src/test/java/com/example/TestHelper.java", """
                package com.example;

                public final class TestHelper {
                    public static String message() {
                        return Main.message();
                    }
                }
                """);
        Path groovySource = source(projectDir, "src/test/groovy/com/example/MainSpec.groovy", """
                package com.example

                final class MainSpec {
                    String message() {
                        return TestHelper.message()
                    }
                }
                """);
        List<List<String>> groovyCommands = new java.util.ArrayList<>();
        TestCompileService service = new TestCompileService(
                new BuildService(),
                new SourceDiscoverer(),
                new ResourceCopier(),
                new BuildFingerprintService(),
                new sh.zolt.doctor.JdkDetector(),
                new JavacRunner(),
                new GroovyCompilerRunner(":", command -> {
                    groovyCommands.add(command);
                    return new GroovyCompilerRunner.ProcessResult(0, "groovy compiled\n");
                }));

        TestCompileResult result = service.compileTests(
                projectDir,
                config().withBuildSettings(new BuildSettings(
                        "src/main/java",
                        "src/test/java",
                        "target/classes",
                        "target/test-classes",
                        List.of("src/test/java"),
                        List.of("src/test/groovy"))),
                projectDir.resolve("cache"));

        assertEquals(2, result.sourceCount());
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/TestHelper.class")));
        assertEquals(1, groovyCommands.size());
        List<String> command = groovyCommands.getFirst();
        assertTrue(command.contains(groovySource.normalize().toString()));
        assertTrue(command.contains("-classpath"));
        String classpath = command.get(command.indexOf("-classpath") + 1);
        assertTrue(classpath.contains(projectDir.resolve("target/test-classes").toString()));
        assertTrue(classpath.contains(projectDir.resolve("target/classes").toString()));
        assertTrue(result.compilerOutput().contains("groovy compiled"));
    }

    static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "com.example",
                        currentJavaMajorVersion(),
                        Optional.of("com.example.Main")),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    static Path source(Path projectDir, String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    static void writeLockfile(Path projectDir, String content) throws IOException {
        write(projectDir, content);
    }
}
