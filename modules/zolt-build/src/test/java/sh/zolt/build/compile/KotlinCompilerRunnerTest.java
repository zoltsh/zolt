package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.classpath.Classpath;

final class KotlinCompilerRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void isolatesLauncherClosureAndUsesReleaseApiDeterministically() {
        List<List<String>> commands = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            commands.add(command);
            return new KotlinCompilerRunner.ProcessResult(0, "compiled kotlin\n");
        });
        Path output = tempDir.resolve("classes");

        JavacResult result = runner.compile(
                Path.of("/managed-jdk/bin/java"),
                Path.of("/managed-jdk"),
                List.of(Path.of("src/Z.kt"), Path.of("src/A.kt")),
                new Classpath(List.of(Path.of("cache/compiler.jar"), Path.of("cache/coroutines.jar"))),
                new Classpath(List.of(Path.of("cache/kotlin-stdlib.jar"), Path.of("cache/dependency.jar"))),
                output,
                new KotlinCompilerRunner.Options("21", "demo_main", false));

        assertEquals(2, result.sourceCount());
        assertEquals("compiled kotlin\n", result.output());
        assertEquals(List.of(
                "/managed-jdk/bin/java",
                "-cp",
                "cache/compiler.jar:cache/coroutines.jar",
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib",
                "-no-reflect",
                "-jdk-home",
                "/managed-jdk",
                "-Xjdk-release=21",
                "-classpath",
                "cache/kotlin-stdlib.jar:cache/dependency.jar",
                "-module-name",
                "demo_main",
                "-d",
                output.toString(),
                "src/A.kt",
                "src/Z.kt"), commands.getFirst());
        assertFalse(commands.getFirst().get(2).contains("dependency.jar"));
        assertFalse(commands.getFirst().get(10).contains("compiler.jar"));
    }

    @Test
    void hostApiModeTargetsJavaEightWithoutPinningRelease() {
        List<List<String>> commands = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            commands.add(command);
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("/jdk/bin/java"),
                Path.of("/jdk"),
                List.of(Path.of("src/Main.kt")),
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("host-classes"),
                new KotlinCompilerRunner.Options("8", "host_main", true));

        List<String> command = commands.getFirst();
        assertTrue(command.contains("-jvm-target"));
        assertTrue(command.contains("1.8"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("-Xjdk-release=")));
    }

    @Test
    void releaseApiOnJdkEightUsesTheSelectedJdkAndJvmTarget() {
        List<List<String>> commands = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            commands.add(command);
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("/jdk8/bin/java"),
                Path.of("/jdk8"),
                List.of(Path.of("src/Main.kt")),
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("jdk8-classes"),
                new KotlinCompilerRunner.Options("8", "jdk8_main", false, false));

        List<String> command = commands.getFirst();
        assertTrue(command.contains("-jdk-home"));
        assertTrue(command.contains("/jdk8"));
        assertTrue(command.contains("-jvm-target"));
        assertTrue(command.contains("1.8"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("-Xjdk-release=")));
    }

    @Test
    void emptySourceSetCreatesOutputWithoutStartingCompiler() {
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            throw new AssertionError("compiler must not run");
        });
        Path output = tempDir.resolve("empty");

        JavacResult result = runner.compile(
                Path.of("/jdk/bin/java"),
                Path.of("/jdk"),
                List.of(),
                new Classpath(List.of()),
                new Classpath(List.of()),
                output,
                new KotlinCompilerRunner.Options("21", "empty_main", false));

        assertEquals(0, result.sourceCount());
        assertTrue(java.nio.file.Files.isDirectory(output));
    }

    @Test
    void compilerFailurePreservesExitCodeAndDiagnostics() {
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command ->
                new KotlinCompilerRunner.ProcessResult(2, "internal compiler failure\n"));

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> runner.compile(
                        Path.of("/jdk/bin/java"),
                        Path.of("/jdk"),
                        List.of(Path.of("src/Main.kt")),
                        new Classpath(List.of(Path.of("compiler.jar"))),
                        new Classpath(List.of(Path.of("stdlib.jar"))),
                        tempDir.resolve("failed"),
                        new KotlinCompilerRunner.Options("21", "failed_main", false)));

        assertTrue(failure.getMessage().contains("exit code 2"));
        assertTrue(failure.getMessage().contains("internal compiler failure"));
        assertTrue(failure.getMessage().contains("[toolchain.kotlin]"));
        assertTrue(failure.getMessage().contains("[dependencies]"));
    }

    @Test
    void optionsRejectBlankReleaseAndModuleName() {
        assertThrows(
                KotlinCompileException.class,
                () -> new KotlinCompilerRunner.Options(" ", "main", false));
        assertThrows(
                KotlinCompileException.class,
                () -> new KotlinCompilerRunner.Options("21", " ", false));
    }
}
