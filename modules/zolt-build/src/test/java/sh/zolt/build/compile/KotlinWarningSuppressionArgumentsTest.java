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

/** Qualifies shared Kotlin/javac warning suppression. */
final class KotlinWarningSuppressionArgumentsTest {
    @TempDir
    private Path tempDir;

    @Test
    void mapsWarningSuppressionToBothCompilersAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of("-nowarn"))
                .withFriendPath(Path.of("target/classes"));

        assertTrue(options.suppressWarnings());
        assertEquals(
                List.of("-nowarn"),
                KotlinCompileOptionsPolicy.javacOptions(options).arguments());

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
        assertEquals(1, lines.stream().filter("\"-nowarn\""::equals).count());
        assertTrue(lines.contains("\"-Xfriend-paths=target/classes\""), lines.toString());
    }

    @Test
    void scopesWarningSuppressionToTheActiveCompilerLane() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of("-nowarn"),
                List.of());
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of("-nowarn"),
                List.of());

        assertTrue(main.suppressWarnings());
        assertFalse(test.suppressWarnings());
    }

    @Test
    void rejectsDuplicateAndContradictoryWarningPoliciesActionably() {
        KotlinCompileException duplicate = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.MAIN,
                        List.of("-nowarn", "-nowarn"),
                        List.of()));
        assertTrue(duplicate.getMessage().contains("duplicate compiler argument"));
        assertTrue(duplicate.getMessage().contains("-nowarn"));

        for (String enforcement : List.of("-Werror", "-Wextra")) {
            KotlinCompileException incompatible = assertThrows(
                    KotlinCompileException.class,
                    () -> options(
                            KotlinCompilationScope.TEST,
                            List.of(),
                            List.of(enforcement, "-nowarn")));
            assertTrue(incompatible.getMessage().contains("[compiler.test].args"));
            assertTrue(incompatible.getMessage().contains("incompatible compiler arguments"));
            assertTrue(incompatible.getMessage().contains("-nowarn"));
            assertTrue(incompatible.getMessage().contains(enforcement));
        }
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
