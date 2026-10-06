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

/** Qualifies bounded Kotlin language and API version compiler arguments. */
final class KotlinLanguageVersionArgumentsTest {
    @TempDir
    private Path tempDir;

    @Test
    void mapsVersionPairsOnlyToKotlinc() {
        KotlinCompilerOptions options = options(
                KotlinCompilationScope.MAIN,
                List.of(
                        "-parameters",
                        "-language-version", "2.1",
                        "-api-version", "1.9",
                        "-Werror"),
                List.of());

        assertEquals("2.1", options.policy().language().languageVersion());
        assertEquals("1.9", options.policy().language().apiVersion());
        assertEquals(
                List.of("-parameters", "-Werror"),
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
                List.of(Path.of("src/Main.kt")),
                new Classpath(List.of(Path.of("compiler.jar"))),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                tempDir.resolve("classes"),
                options);

        List<String> lines = argumentContents.getFirst().lines().toList();
        assertPair(lines, "-language-version", "2.1");
        assertPair(lines, "-api-version", "1.9");
    }

    @Test
    void scopesVersionPairsToMainAndTestCompilation() {
        List<String> mainArguments = List.of("-language-version", "2.0", "-api-version", "1.9");
        List<String> testArguments = List.of("-language-version", "2.1", "-api-version", "2.0");

        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                mainArguments,
                testArguments);
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                mainArguments,
                testArguments);

        assertEquals("2.0", main.policy().language().languageVersion());
        assertEquals("1.9", main.policy().language().apiVersion());
        assertEquals("2.1", test.policy().language().languageVersion());
        assertEquals("2.0", test.policy().language().apiVersion());
    }

    @Test
    void rejectsMissingMalformedAndDuplicateVersionArguments() {
        for (List<String> arguments : List.of(
                List.of("-language-version"),
                List.of("-api-version", "latest"))) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, arguments, List.of()));

            assertTrue(failure.getMessage().contains("[compiler].args"));
            assertTrue(failure.getMessage().contains("invalid Kotlin argument"));
            assertTrue(failure.getMessage().contains("<major.minor>"));
        }

        for (String option : List.of("-language-version", "-api-version")) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> options(
                            KotlinCompilationScope.TEST,
                            List.of(),
                            List.of(option, "1.9", option, "2.0")));

            assertTrue(failure.getMessage().contains("[compiler.test].args"));
            assertTrue(failure.getMessage().contains("duplicate compiler argument"));
            assertTrue(failure.getMessage().contains("`" + option + "`"));
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

    private static void assertPair(
            List<String> lines,
            String option,
            String value) {
        int index = lines.indexOf("\"" + option + "\"");
        assertTrue(index >= 0, lines.toString());
        assertEquals("\"" + value + "\"", lines.get(index + 1));
    }
}
