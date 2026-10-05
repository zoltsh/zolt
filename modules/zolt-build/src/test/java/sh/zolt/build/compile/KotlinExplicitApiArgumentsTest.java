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

/** Qualifies the bounded, kotlinc-only explicit-API mode. */
final class KotlinExplicitApiArgumentsTest {
    @TempDir
    private Path tempDir;

    @Test
    void mapsExplicitApiOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of("-Xexplicit-api=warning"))
                .withFriendPath(Path.of("target/classes"));

        assertEquals("warning", options.explicitApiMode());
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
        assertTrue(lines.contains("\"-Xexplicit-api=warning\""), lines.toString());
        assertTrue(lines.contains("\"-Xfriend-paths=target/classes\""), lines.toString());
    }

    @Test
    void acceptsEveryModeAndScopesItToTheActiveCompilerLane() {
        for (String mode : List.of("strict", "warning", "disable")) {
            KotlinCompilerOptions main = options(
                    KotlinCompilationScope.MAIN,
                    List.of("-Xexplicit-api=" + mode),
                    List.of("-Xexplicit-api=disable"));
            assertEquals(mode, main.explicitApiMode());
        }

        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of("-Xexplicit-api=strict"),
                List.of("-Xexplicit-api=warning"));
        assertEquals("warning", test.explicitApiMode());
    }

    @Test
    void rejectsMalformedAndDuplicateModesActionably() {
        for (String argument : List.of(
                "-Xexplicit-api=",
                "-Xexplicit-api=error",
                "-Xexplicit-api=STRICT")) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));

            assertTrue(failure.getMessage().contains("[compiler].args"));
            assertTrue(failure.getMessage().contains("invalid Kotlin explicit-API argument"));
            assertTrue(failure.getMessage().contains("-Xexplicit-api=warning"));
        }

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of("-Xexplicit-api=warning", "-Xexplicit-api=strict")));
        assertTrue(failure.getMessage().contains("[compiler.test].args"));
        assertTrue(failure.getMessage().contains("duplicate compiler argument"));
        assertTrue(failure.getMessage().contains("-Xexplicit-api=strict"));
    }

    private static KotlinCompilerOptions options(
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
