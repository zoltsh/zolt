package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.build.incremental.IncrementalCompilePlanner;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;

final class MainCompileSourceExecutorTest {
    @TempDir
    private Path projectDir;

    @Test
    void skippedGroovyCompileCountsEveryMainSourceWithoutInvokingCompiler() {
        GroovyCompilerRunner runner = new GroovyCompilerRunner(":", command -> {
            throw new AssertionError("Groovy compiler must not run for reused output");
        });
        MainCompileSourceExecutor executor = new MainCompileSourceExecutor(null, runner, null, null);
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(Path.of("src/main/java/com/example/JavaApi.java")),
                List.of(Path.of("src/main/java/com/example/GroovyApi.groovy")),
                List.of(),
                List.of());

        MainCompileSourceExecutor.Attempt result = executor.compile(
                true,
                projectDir,
                config(),
                sources,
                classpaths(List.of(), List.of()),
                projectDir.resolve("target/classes"),
                projectDir.resolve("target/generated/sources/annotations"),
                jdkStatus("21.0.11"));

        assertEquals(2, result.sourceCount());
        assertEquals("skipped", result.mode());
        assertEquals(List.of(), result.compiledSources());
    }

    @Test
    void jointlyCompilesJavaAndGroovyAfterResettingOwnedOutputs() throws IOException {
        Path javaSource = source("src/main/java/com/example/JavaApi.java", "class JavaApi {}");
        Path groovySource = source("src/main/java/com/example/GroovyApi.groovy", "class GroovyApi {}");
        Path output = projectDir.resolve("target/classes");
        Path generated = projectDir.resolve("target/generated/sources/annotations");
        Path staleClass = output.resolve("com/example/Stale.class");
        Path staleState = output.resolve(".zolt-incremental-main.state");
        Path staleGenerated = generated.resolve("com/example/Stale.java");
        Files.createDirectories(staleClass.getParent());
        Files.createDirectories(staleGenerated.getParent());
        Files.write(staleClass, new byte[] {1});
        Files.writeString(staleState, "stale");
        Files.writeString(staleGenerated, "class Stale {}");
        List<List<String>> commands = new ArrayList<>();
        GroovyCompilerRunner runner = new GroovyCompilerRunner(":", command -> {
            assertFalse(Files.exists(staleClass));
            assertFalse(Files.exists(staleState));
            assertFalse(Files.exists(staleGenerated));
            commands.add(command);
            return new GroovyCompilerRunner.ProcessResult(0, "joint compile\n");
        });
        SourceDiscoveryResult sources = new SourceDiscoveryResult(
                List.of(javaSource), List.of(groovySource), List.of(), List.of());

        MainCompileSourceExecutor.Attempt result = executor(runner).compile(
                false,
                projectDir,
                config(),
                sources,
                classpaths(List.of(projectDir.resolve("cache/groovy.jar")), List.of()),
                output,
                generated,
                jdkStatus("21.0.11"));

        assertEquals(2, result.sourceCount());
        assertEquals("full", result.mode());
        assertEquals("groovy-main-sources", result.fallbackReason());
        assertEquals(2, result.diagnostics().sourcesRecompiled());
        assertEquals(sources.allMainSources(), result.compiledSources());
        assertEquals("joint compile\n", result.output());
        assertEquals("/managed-jdk/bin/java", commands.getFirst().getFirst());
        assertTrue(commands.getFirst().contains("-J=-release=21"));
        assertTrue(commands.getFirst().contains(javaSource.toString()));
        assertTrue(commands.getFirst().contains(groovySource.toString()));
    }

    @Test
    void rejectsUnsupportedGroovyConfigurationBeforeDeletingOutput() throws IOException {
        Path groovySource = source("src/main/java/com/example/Main.groovy", "class Main {}");
        Path output = projectDir.resolve("target/classes");
        Path staleClass = output.resolve("com/example/StillHere.class");
        Files.createDirectories(staleClass.getParent());
        Files.write(staleClass, new byte[] {1});
        GroovyCompilerRunner runner = new GroovyCompilerRunner(":", command -> {
            throw new AssertionError("Groovy compiler must not run after failed preflight");
        });

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> executor(runner).compile(
                        false,
                        projectDir,
                        config(),
                        new SourceDiscoveryResult(
                                List.of(), List.of(groovySource), List.of(), List.of()),
                        classpaths(List.of(), List.of(projectDir.resolve("cache/processor.jar"))),
                        output,
                        projectDir.resolve("target/generated/sources/annotations"),
                        jdkStatus("21.0.11")));

        assertTrue(exception.getMessage().contains("[dependencies.processor]"));
        assertTrue(Files.exists(staleClass));
    }

    private static MainCompileSourceExecutor executor(GroovyCompilerRunner runner) {
        return new MainCompileSourceExecutor(
                new JavacRunner(),
                runner,
                new IncrementalCompileStateRecorder(),
                new IncrementalCompilePlanner());
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static ClasspathSet classpaths(List<Path> compileEntries, List<Path> processorEntries) {
        Classpath compile = new Classpath(compileEntries);
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(
                compile,
                empty,
                empty,
                empty,
                new Classpath(processorEntries),
                empty,
                empty);
    }

    private static JdkStatus jdkStatus(String version) {
        return new JdkStatus(
                Optional.of(Path.of("/managed-jdk")),
                Optional.of(Path.of("/managed-jdk/bin/java")),
                Optional.of(Path.of("/managed-jdk/bin/javac")),
                Optional.of(Path.of("/managed-jdk/bin/jar")),
                Optional.of(version),
                "21");
    }

    private Path source(String relativePath, String content) throws IOException {
        Path source = projectDir.resolve(relativePath);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }
}
