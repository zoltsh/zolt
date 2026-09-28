package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.GroovyCompileException;
import sh.zolt.classpath.Classpath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GroovyCompilerRunnerTest {
    @Test
    void invokesProjectProvidedGroovyCompilerFromClasspath() {
        List<List<String>> commands = new ArrayList<>();
        GroovyCompilerRunner runner = new GroovyCompilerRunner(":", command -> {
            commands.add(command);
            return new GroovyCompilerRunner.ProcessResult(0, "compiled groovy\n");
        });

        JavacResult result = runner.compile(
                Path.of("/jdk/bin/java"),
                List.of(Path.of("src/test/groovy/com/example/MainSpec.groovy")),
                new Classpath(List.of(Path.of("target/test-classes"), Path.of("cache/groovy.jar"))),
                Path.of("target/test-classes"));

        assertEquals(1, result.sourceCount());
        assertEquals("compiled groovy\n", result.output());
        List<String> command = commands.getFirst();
        assertEquals("/jdk/bin/java", command.get(0));
        assertEquals("-cp", command.get(1));
        assertEquals("target/test-classes:cache/groovy.jar", command.get(2));
        assertEquals("org.codehaus.groovy.tools.FileSystemCompiler", command.get(3));
        assertEquals("-classpath", command.get(4));
        assertEquals("target/test-classes:cache/groovy.jar", command.get(5));
        assertTrue(command.contains("-d"));
        assertTrue(command.contains("target/test-classes"));
        assertTrue(command.contains("-classpath"));
        assertTrue(command.contains("src/test/groovy/com/example/MainSpec.groovy"));
    }

    @Test
    void missingGroovyCompilerProducesActionableError() {
        GroovyCompilerRunner runner = new GroovyCompilerRunner(":", command ->
                new GroovyCompilerRunner.ProcessResult(1, "Could not find or load main class org.codehaus.groovy.tools.FileSystemCompiler\n"));

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> runner.compile(
                        Path.of("/jdk/bin/java"),
                        List.of(Path.of("src/test/groovy/com/example/MainSpec.groovy")),
                        new Classpath(List.of(Path.of("target/test-classes"))),
                        Path.of("target/test-classes")));

        assertTrue(exception.getMessage().contains("Groovy test compilation failed with exit code 1"));
        assertTrue(exception.getMessage().contains("org.apache.groovy:groovy"));
        assertTrue(exception.getMessage().contains("[dependencies.test]"));
    }

    @Test
    void jointCompilationUsesSelectedJdkAndReproducibleOptions() {
        List<List<String>> commands = new ArrayList<>();
        GroovyCompilerRunner runner = new GroovyCompilerRunner(":", command -> {
            commands.add(command);
            return new GroovyCompilerRunner.ProcessResult(0, "compiled jointly\n");
        });

        JavacResult result = runner.compileJoint(
                Path.of("/managed-jdk/bin/java"),
                List.of(
                        Path.of("src/main/groovy/com/example/GroovyApi.groovy"),
                        Path.of("src/main/java/com/example/JavaApi.java")),
                new Classpath(List.of(Path.of("cache/groovy.jar"), Path.of("cache/dependency.jar"))),
                Path.of("target/classes"),
                new GroovyCompilerRunner.JointOptions("21", "UTF-16", false));

        assertEquals(2, result.sourceCount());
        assertEquals("compiled jointly\n", result.output());
        assertEquals(List.of(
                "/managed-jdk/bin/java",
                "-Dgroovy.target.bytecode=21",
                "-cp",
                "cache/groovy.jar:cache/dependency.jar",
                "org.codehaus.groovy.tools.FileSystemCompiler",
                "-classpath",
                "cache/groovy.jar:cache/dependency.jar",
                "-j",
                "-d",
                "target/classes",
                "--encoding=UTF-16",
                "-J=-release=21",
                "-F=proc:none",
                "src/main/groovy/com/example/GroovyApi.groovy",
                "src/main/java/com/example/JavaApi.java"), commands.getFirst());
    }

    @Test
    void jointCompilationMapsHostPlatformModeAndDefaultsEncoding() {
        List<List<String>> commands = new ArrayList<>();
        GroovyCompilerRunner runner = new GroovyCompilerRunner(":", command -> {
            commands.add(command);
            return new GroovyCompilerRunner.ProcessResult(0, "");
        });

        runner.compileJoint(
                Path.of("/jdk/bin/java"),
                List.of(Path.of("src/main/groovy/Main.groovy")),
                new Classpath(List.of(Path.of("cache/groovy.jar"))),
                Path.of("target/classes"),
                new GroovyCompilerRunner.JointOptions("17", "", true));

        List<String> command = commands.getFirst();
        assertTrue(command.contains("--encoding=UTF-8"));
        assertTrue(command.contains("-J=source=17"));
        assertTrue(command.contains("-J=target=17"));
        assertFalse(command.contains("-J=-release=17"));
    }

    @Test
    void failedJointCompilationPointsToMainDependencies() {
        GroovyCompilerRunner runner = new GroovyCompilerRunner(":", command ->
                new GroovyCompilerRunner.ProcessResult(1, "compiler missing\n"));

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> runner.compileJoint(
                        Path.of("/jdk/bin/java"),
                        List.of(Path.of("src/main/groovy/Main.groovy")),
                        new Classpath(List.of()),
                        Path.of("target/classes"),
                        new GroovyCompilerRunner.JointOptions("21", "UTF-8", false)));

        assertTrue(exception.getMessage().contains("Groovy main joint compilation failed"));
        assertTrue(exception.getMessage().contains("[dependencies]"));
        assertFalse(exception.getMessage().contains("[dependencies.test]"));
    }

    @Test
    void jointCompilationRequiresAnEffectiveRelease() {
        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> new GroovyCompilerRunner.JointOptions(" ", "UTF-8", false));

        assertTrue(exception.getMessage().contains("effective Java release"));
    }
}
