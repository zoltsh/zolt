package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Qualifies the bounded, kotlinc-only JVM-default compatibility mode. */
final class KotlinJvmDefaultArgumentsTest {
    @TempDir
    private Path tempDir;

    @Test
    void mapsJvmDefaultOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerRunner.Options options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of("-jvm-default=no-compatibility"))
                .withFriendPath(Path.of("target/classes"));

        assertEquals("no-compatibility", options.jvmDefaultMode());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());

        List<String> argumentContents = new ArrayList<>();
        KotlinCompilerRunner runner = new KotlinCompilerRunner(":", command -> {
            argumentContents.add(KotlinCompilerRunnerTest.readString(
                    KotlinCompilerRunnerTest.argumentFile(command)));
            return new KotlinCompilerRunner.ProcessResult(0, "");
        });
        runner.compile(
                Path.of("/jdk/bin/java"),
                Path.of("/jdk"),
                List.of(Path.of("src/Test.kt")),
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("test-classes"),
                options,
                KotlinCompilationScope.TEST);

        List<String> lines = argumentContents.getFirst().lines().toList();
        assertTrue(lines.contains("\"-jvm-default=no-compatibility\""), lines.toString());
        assertTrue(lines.contains("\"-Xfriend-paths=target/classes\""), lines.toString());
    }

    @Test
    void acceptsEveryModeAndScopesItToTheActiveCompilerLane() {
        for (String mode : List.of("enable", "no-compatibility", "disable")) {
            KotlinCompilerRunner.Options main = options(
                    KotlinCompilationScope.MAIN,
                    List.of("-jvm-default=" + mode),
                    List.of("-jvm-default=disable"));
            assertEquals(mode, main.jvmDefaultMode());
        }

        KotlinCompilerRunner.Options test = options(
                KotlinCompilationScope.TEST,
                List.of("-jvm-default=enable"),
                List.of("-jvm-default=disable"));
        assertEquals("disable", test.jvmDefaultMode());
    }

    @Test
    void rejectsMalformedAndDuplicateModesActionably() {
        for (String argument : List.of(
                "-jvm-default=",
                "-jvm-default=compatibility",
                "-jvm-default=ENABLE")) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));

            assertTrue(failure.getMessage().contains("[compiler].args"));
            assertTrue(failure.getMessage().contains("invalid Kotlin JVM-default argument"));
            assertTrue(failure.getMessage().contains("-jvm-default=no-compatibility"));
        }

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of("-jvm-default=enable", "-jvm-default=disable")));
        assertTrue(failure.getMessage().contains("[compiler.test].args"));
        assertTrue(failure.getMessage().contains("duplicate compiler argument"));
        assertTrue(failure.getMessage().contains("-jvm-default=disable"));
    }

    private static KotlinCompilerRunner.Options options(
            KotlinCompilationScope scope,
            List<String> mainArguments,
            List<String> testArguments) {
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "",
                "",
                mainArguments,
                testArguments);
        ProjectConfig config = KotlinMainCompilePolicyTest.config(
                compiler,
                Map.of(),
                Map.of(),
                "demo");
        return KotlinCompileOptionsPolicy.options(
                config,
                KotlinMainCompilePolicyTest.jdkStatus("21.0.11", "21"),
                scope);
    }
}
