package sh.zolt.build.testruntime.compile;

import static sh.zolt.build.TestContentAddressedLockSupport.write;
import static sh.zolt.build.TestContentAddressedLockSupport.cachedJar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.BuildService;
import sh.zolt.build.compile.GroovyCompilerRunner;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.resources.ResourceCopier;
import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
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
        Path cacheRoot = projectDir.resolve("cache");
        Path groovyJar = cacheRoot.resolve("org/apache/groovy/groovy/4.0.24/groovy-4.0.24.jar");
        createFakeGroovyCompilerJar(projectDir, groovyJar, "4.0.24");
        writeLockfile(projectDir, """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "org.apache.groovy:groovy"
                version = "4.0.24"
                lane = "test"
                resolvedScope = "test"

                [[dependencyRoot]]
                member = "."
                id = "com.example:application-dependency"
                version = "1.0.0"
                lane = "test"
                resolvedScope = "test"

                [[package]]
                id = "org.apache.groovy:groovy"
                version = "4.0.24"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "org/apache/groovy/groovy/4.0.24/groovy-4.0.24.jar"
                dependencies = []

                [[package]]
                id = "com.example:application-dependency"
                version = "1.0.0"
                source = "maven-central"
                scope = "test"
                direct = true
                jar = "com/example/application-dependency/1.0.0/application-dependency-1.0.0.jar"
                dependencies = []
                """);
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
                cacheRoot);

        assertEquals(2, result.sourceCount());
        assertTrue(Files.exists(projectDir.resolve("target/test-classes/com/example/TestHelper.class")));
        assertEquals(1, groovyCommands.size());
        List<String> command = groovyCommands.getFirst();
        assertTrue(command.contains(groovySource.normalize().toString()));
        String launcherClasspath = command.get(command.indexOf("-cp") + 1);
        Path selectedCompiler = cachedJar(projectDir, "org.apache.groovy:groovy")
                .toAbsolutePath()
                .normalize();
        Path applicationDependency = cachedJar(projectDir, "com.example:application-dependency")
                .toAbsolutePath()
                .normalize();
        assertEquals(selectedCompiler.toString(), launcherClasspath);
        assertFalse(launcherClasspath.contains(projectDir.resolve("target/classes").toString()));
        assertFalse(launcherClasspath.contains(projectDir.resolve("target/test-classes").toString()));
        assertFalse(launcherClasspath.contains(applicationDependency.toString()));
        assertTrue(command.contains("-classpath"));
        String classpath = command.get(command.indexOf("-classpath") + 1);
        assertTrue(classpath.contains(projectDir.resolve("target/test-classes").toString()));
        assertTrue(classpath.contains(projectDir.resolve("target/classes").toString()));
        assertTrue(classpath.contains(applicationDependency.toString()));
        assertTrue(result.compilerOutput().contains("groovy compiled"));
    }

    @Test
    void legacyCompilePathWithoutVerifiedPackageMetadataFailsBeforeOutputCleanup() throws IOException {
        writeLockfile(projectDir, "version = 7\n");
        source(projectDir, "src/test/groovy/com/example/MainSpec.groovy", """
                package com.example

                final class MainSpec {
                }
                """);
        Path staleOutput = source(projectDir, "target/test-classes/stale/Existing.class", "stale\n");
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
                    return new GroovyCompilerRunner.ProcessResult(0, "unexpected\n");
                }));
        Classpath empty = new Classpath(List.of());
        ClasspathSet classpaths = new ClasspathSet(empty, empty, empty, empty, empty, empty, empty);
        BuildResult buildResult = new BuildResult(
                Optional.empty(),
                0,
                0,
                projectDir.resolve("target/classes"),
                "");

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> service.compileTests(
                        projectDir,
                        config().withBuildSettings(new BuildSettings(
                                "src/main/java",
                                "src/test/java",
                                "target/classes",
                                "target/test-classes",
                                List.of("src/test/java"),
                                List.of("src/test/groovy"))),
                        classpaths,
                        buildResult));

        assertTrue(exception.getMessage().contains("not present in the verified resolved packages"));
        assertTrue(exception.getMessage().contains("[dependencies] or [dependencies.test]"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
        assertEquals("stale\n", Files.readString(staleOutput));
        assertTrue(groovyCommands.isEmpty());
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
