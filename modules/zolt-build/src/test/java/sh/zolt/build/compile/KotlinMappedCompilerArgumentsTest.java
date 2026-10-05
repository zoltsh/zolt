package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.classpath.Classpath;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;

/** Qualifies the bounded javac-to-kotlinc argument mapping. */
final class KotlinMappedCompilerArgumentsTest {
    @TempDir
    private Path tempDir;

    @Test
    void emitsWarningsAsErrorsExactlyOnceWhenRequested() {
        List<String> argumentContents = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            Path argumentFile = KotlinCompilerRunnerTest.argumentFile(command);
            argumentContents.add(KotlinCompilerRunnerTest.readString(argumentFile));
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });

        runner.compile(
                Path.of("/jdk/bin/java"),
                Path.of("/jdk"),
                List.of(Path.of("src/Main.kt")),
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("warnings-as-errors-classes"),
                new KotlinCompilerOptions(
                        "21", "warnings_as_errors_main", false, true, false, true));

        List<String> arguments = argumentContents.getFirst().lines().toList();
        assertEquals(
                1,
                arguments.stream().filter("\"-Werror\""::equals).count(),
                arguments.toString());
    }

    @Test
    void defaultsWarningsOffAndFriendPathRetainsMappedArguments() {
        KotlinCompilerOptions defaults = new KotlinCompilerOptions("21", "main", false);
        KotlinCompilerOptions mapped = new KotlinCompilerOptions(
                        "21", "test", false, true, true, true)
                .withFriendPath(Path.of("target/classes"));

        assertFalse(defaults.javaParameters());
        assertFalse(defaults.warningsAsErrors());
        assertTrue(mapped.javaParameters());
        assertTrue(mapped.warningsAsErrors());
        assertEquals(Path.of("target/classes"), mapped.friendPath());
    }

    @Test
    void acceptsWarningsAsErrorsAndCanonicalizesBothMappedArguments() {
        for (List<String> arguments : List.of(
                List.of("-Werror"),
                List.of("-parameters", "-Werror"),
                List.of("-Werror", "-parameters"))) {
            KotlinCompilerOptions options = mainOptions(arguments, List.of());

            assertEquals(arguments.contains("-parameters"), options.javaParameters());
            assertTrue(options.warningsAsErrors());
            assertEquals(
                    arguments.contains("-parameters")
                            ? List.of("-parameters", "-Werror")
                            : List.of("-Werror"),
                    KotlinCompileOptionsPolicy.javacOptions(options).arguments());
        }
    }

    @Test
    void scopesMappedArgumentsToTheActiveCompilerLane() {
        ProjectConfig config = config(
                List.of("-Werror"),
                List.of("-parameters"));

        KotlinCompilerOptions main = KotlinCompileOptionsPolicy.options(
                config,
                KotlinMainCompilePolicyTest.jdkStatus("21.0.11", "21"),
                KotlinCompilationScope.MAIN);
        KotlinCompilerOptions test = KotlinCompileOptionsPolicy.options(
                config,
                KotlinMainCompilePolicyTest.jdkStatus("21.0.11", "21"),
                KotlinCompilationScope.TEST);

        assertFalse(main.javaParameters());
        assertTrue(main.warningsAsErrors());
        assertEquals(
                List.of("-Werror"),
                KotlinCompileOptionsPolicy.javacOptions(main).arguments());
        assertTrue(test.javaParameters());
        assertFalse(test.warningsAsErrors());
        assertEquals(
                List.of("-parameters"),
                KotlinCompileOptionsPolicy.javacOptions(test).arguments());
    }

    @Test
    void rejectsUnsupportedMainArgumentsActionably() {
        for (List<String> arguments : List.of(
                List.of("-Xlint:all"),
                List.of("-parameters", "-Xlint:all"),
                List.of("-Werror", "-Xlint:all"))) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> mainOptions(arguments, List.of()));

            assertTrue(failure.getMessage().contains("[compiler].args"));
            assertTrue(failure.getMessage().contains("unsupported compiler argument"));
            assertTrue(failure.getMessage().contains("-Xlint:all"));
            assertTrue(failure.getMessage().contains("-parameters"));
            assertTrue(failure.getMessage().contains("-Werror"));
        }
    }

    @Test
    void rejectsDuplicateMappedMainArgumentsActionably() {
        for (String duplicate : List.of("-parameters", "-Werror")) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> mainOptions(List.of(duplicate, duplicate), List.of()));

            assertTrue(failure.getMessage().contains("[compiler].args"));
            assertTrue(failure.getMessage().contains("duplicate compiler argument"));
            assertTrue(failure.getMessage().contains("`" + duplicate + "`"));
            assertTrue(failure.getMessage().contains("at most once"));
        }
    }

    private static KotlinCompilerOptions mainOptions(
            List<String> arguments,
            List<String> testArguments) {
        return KotlinMainCompilePolicy.options(
                config(arguments, testArguments),
                KotlinMainCompilePolicyTest.sources(
                        List.of(Path.of("src/main/java/Main.java")),
                        List.of(),
                        List.of(KotlinMainCompilePolicyTest.KOTLIN)),
                KotlinMainCompilePolicyTest.classpaths(List.of()),
                KotlinMainCompilePolicyTest.jdkStatus("21.0.11", "21"));
    }

    private static ProjectConfig config(
            List<String> arguments,
            List<String> testArguments) {
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "",
                "",
                arguments,
                testArguments);
        return KotlinMainCompilePolicyTest.config(
                compiler,
                Map.of(),
                Map.of(),
                "demo");
    }
}
