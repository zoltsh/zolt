package sh.zolt.build.compile;

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

/** Qualifies the source-set-scoped, kotlinc-only progressive mode. */
final class KotlinProgressiveArgumentsTest {
    @TempDir
    private Path tempDir;

    @Test
    void mapsProgressiveModeOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of("-progressive"))
                .withFriendPath(Path.of("target/classes"));

        assertTrue(options.policy().language().progressiveMode());
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
        assertTrue(lines.contains("\"-progressive\""), lines.toString());
        assertTrue(lines.contains("\"-Xfriend-paths=target/classes\""), lines.toString());
    }

    @Test
    void scopesProgressiveModeToTheActiveCompilerLane() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of("-progressive"),
                List.of());
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of("-progressive"),
                List.of());

        assertTrue(main.policy().language().progressiveMode());
        assertFalse(test.policy().language().progressiveMode());
    }

    @Test
    void rejectsDuplicateProgressiveModeActionably() {
        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of("-progressive", "-progressive")));

        assertTrue(failure.getMessage().contains("[compiler.test].args"));
        assertTrue(failure.getMessage().contains("duplicate compiler argument"));
        assertTrue(failure.getMessage().contains("-progressive"));
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
