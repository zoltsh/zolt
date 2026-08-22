package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("cache/groovy.jar:target/test-classes", command.get(2));
        assertTrue(command.contains("org.codehaus.groovy.tools.FileSystemCompiler"));
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
}
