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

/** Qualifies repeatable, kotlinc-only experimental API opt-ins. */
final class KotlinOptInArgumentsTest {
    @TempDir
    private Path tempDir;

    @Test
    void mapsDistinctOptInsOnlyToKotlincAndPreservesThemWithFriendOutput() {
        KotlinCompilerRunner.Options options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(
                                "-opt-in=kotlin.ExperimentalStdlibApi",
                                "-opt-in=com.example.ExperimentalFeature"))
                .withFriendPath(Path.of("target/classes"));

        assertEquals(
                List.of("kotlin.ExperimentalStdlibApi", "com.example.ExperimentalFeature"),
                options.optIns());
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
        assertTrue(lines.contains("\"-opt-in=kotlin.ExperimentalStdlibApi\""), lines.toString());
        assertTrue(lines.contains("\"-opt-in=com.example.ExperimentalFeature\""), lines.toString());
        assertTrue(lines.contains("\"-Xfriend-paths=target/classes\""), lines.toString());
    }

    @Test
    void scopesOptInsToTheActiveCompilerLane() {
        KotlinCompilerRunner.Options main = options(
                KotlinCompilationScope.MAIN,
                List.of("-opt-in=com.example.MainExperimental"),
                List.of("-opt-in=com.example.TestExperimental"));
        KotlinCompilerRunner.Options test = options(
                KotlinCompilationScope.TEST,
                List.of("-opt-in=com.example.MainExperimental"),
                List.of("-opt-in=com.example.TestExperimental"));

        assertEquals(List.of("com.example.MainExperimental"), main.optIns());
        assertEquals(List.of("com.example.TestExperimental"), test.optIns());
    }

    @Test
    void rejectsMalformedAndDuplicateOptInsActionably() {
        for (String argument : List.of(
                "-opt-in=",
                "-opt-in=bad-name",
                "-opt-in=.Bad",
                "-opt-in=com.example..Bad")) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));

            assertTrue(failure.getMessage().contains("[compiler].args"));
            assertTrue(failure.getMessage().contains("invalid Kotlin opt-in argument"));
            assertTrue(failure.getMessage().contains("-opt-in=<qualified.annotation.Name>"));
        }

        String duplicate = "-opt-in=kotlin.ExperimentalStdlibApi";
        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(duplicate, duplicate)));
        assertTrue(failure.getMessage().contains("[compiler.test].args"));
        assertTrue(failure.getMessage().contains("duplicate compiler argument"));
        assertTrue(failure.getMessage().contains(duplicate));
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
