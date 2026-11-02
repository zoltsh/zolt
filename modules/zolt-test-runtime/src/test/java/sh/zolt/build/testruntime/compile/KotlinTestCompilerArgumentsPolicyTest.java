package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.build.testruntime.compile.KotlinTestCompilePolicyTest.classpaths;
import static sh.zolt.build.testruntime.compile.KotlinTestCompilePolicyTest.config;
import static sh.zolt.build.testruntime.compile.KotlinTestCompilePolicyTest.jdkStatus;
import static sh.zolt.build.testruntime.compile.KotlinTestCompilePolicyTest.sources;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.JavacOptions;
import sh.zolt.build.compile.KotlinCompileOptionsPolicy;
import sh.zolt.build.compile.KotlinCompilerOptions;
import sh.zolt.project.CompilerSettings;

/** The bounded javac-to-kotlinc argument mapping for Kotlin test source sets. */
final class KotlinTestCompilerArgumentsPolicyTest {
    private static final Path KOTLIN_MAIN =
            Path.of("src/main/kotlin/com/example/Demo.kt");
    private static final Path JAVA_TEST =
            Path.of("src/test/java/com/example/DemoTest.java");
    private static final Path KOTLIN_TEST =
            Path.of("src/test/kotlin/com/example/DemoTest.kt");

    @Test
    void acceptsEverySupportedTestArgumentSubsetAndCanonicalizesJavacArguments() {
        for (List<String> testArgs : List.of(
                List.<String>of(),
                List.of("-parameters"),
                List.of("-Werror"),
                List.of("-parameters", "-Werror"),
                List.of("-Werror", "-parameters"))) {
            KotlinCompilerOptions options = options(
                    new CompilerSettings(null, null, "", "", List.of(), testArgs),
                    List.of(KOTLIN_MAIN),
                    List.of(JAVA_TEST),
                    Path.of("target/classes"));
            JavacOptions javac = KotlinCompileOptionsPolicy.javacOptions(options);

            assertEquals("21", javac.release());
            assertEquals("UTF-8", javac.encoding());
            assertEquals(canonicalArguments(testArgs), javac.arguments());
            assertFalse(javac.hostPlatformApi());
            assertTrue(javac.useJdkRelease());
            assertEquals(testArgs.contains("-parameters"), options.policy().jvmInterop().javaParameters());
            assertEquals(testArgs.contains("-Werror"), options.policy().diagnostics().warningsAsErrors());
            assertEquals(Path.of("target/classes"), options.friendPath());
        }
    }

    @Test
    void scopesMappedArgumentsToTheTestCompilerLane() {
        CompilerSettings compiler = new CompilerSettings(
                null,
                null,
                "",
                "",
                List.of("-Xlint:all"),
                List.of("-Werror"));

        KotlinCompilerOptions options = options(
                compiler,
                List.of(),
                List.of(),
                null);

        assertFalse(options.policy().jvmInterop().javaParameters());
        assertTrue(options.policy().diagnostics().warningsAsErrors());
        assertEquals(
                List.of("-Werror"),
                KotlinCompileOptionsPolicy.javacOptions(options).arguments());
    }

    @Test
    void rejectsDuplicateTestCompilerArguments() {
        for (List<String> testArgs : List.of(
                List.of("-parameters", "-parameters"),
                List.of("-Werror", "-Werror"),
                List.of("-parameters", "-Werror", "-parameters"))) {
            KotlinCompileException failure = assertRejected(testArgs);

            assertTrue(failure.getMessage().contains("[compiler.test].args"));
            assertTrue(failure.getMessage().contains("duplicate compiler argument"));
        }
    }

    @Test
    void rejectsUnsupportedTestCompilerArguments() {
        for (List<String> testArgs : List.of(
                List.of("-Xlint:all"),
                List.of("-parameters", "-Xlint:all"),
                List.of("-Werror", "-Xlint:all"))) {
            KotlinCompileException failure = assertRejected(testArgs);

            assertTrue(failure.getMessage().contains("[compiler.test].args"));
            assertTrue(failure.getMessage().contains("unsupported compiler argument"));
            assertTrue(failure.getMessage().contains("duplicate-free subset"));
        }
    }

    private static KotlinCompilerOptions options(
            CompilerSettings compiler,
            List<Path> kotlinMain,
            List<Path> javaTest,
            Path mainOutput) {
        return KotlinTestCompilePolicy.options(
                config(compiler, Map.of(), Map.of(), Map.of()),
                sources(
                        List.of(),
                        List.of(),
                        kotlinMain,
                        javaTest,
                        List.of(),
                        List.of(KOTLIN_TEST)),
                classpaths(List.of()),
                jdkStatus(),
                mainOutput);
    }

    private static KotlinCompileException assertRejected(List<String> testArgs) {
        CompilerSettings compiler = new CompilerSettings(
                null, null, "", "", List.of(), testArgs);
        return assertThrows(
                KotlinCompileException.class,
                () -> options(compiler, List.of(), List.of(), null));
    }

    private static List<String> canonicalArguments(List<String> arguments) {
        if (arguments.contains("-parameters") && arguments.contains("-Werror")) {
            return List.of("-parameters", "-Werror");
        }
        if (arguments.contains("-parameters")) {
            return List.of("-parameters");
        }
        return arguments.contains("-Werror") ? List.of("-Werror") : List.of();
    }
}
