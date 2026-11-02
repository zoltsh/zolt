package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.compile.JavacResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class TestCompileSourceExecutorAttemptTest {
    @Test
    void sourceCountAndOutputCombineAllCompilerResults() {
        TestCompileAttempt attempt = attempt(
                "javac output", "groovy output", "kotlin output");

        assertEquals(9, attempt.sourceCount());
        assertEquals(Path.of("target/test-classes"), attempt.outputDirectory());
        assertEquals("kotlin output\njavac output\ngroovy output", attempt.output());
        assertEquals("full", attempt.mode());
        assertEquals("fallback", attempt.fallbackReason());
        assertEquals(new CompileDiagnostics(1, 2, 3, 4, 5, 6, 7, 8), attempt.diagnostics());
    }

    @Test
    void outputUsesGroovyOutputWhenJavacOutputIsBlank() {
        assertEquals("groovy output", attempt("", "groovy output", "").output());
        assertEquals("groovy output", attempt(null, "groovy output", null).output());
    }

    @Test
    void outputUsesJavacOutputWhenGroovyOutputIsBlank() {
        assertEquals("javac output", attempt("javac output", "", "").output());
        assertEquals("javac output", attempt("javac output", null, null).output());
    }

    @Test
    void outputUsesKotlinOutputWhenOtherCompilerOutputsAreBlank() {
        assertEquals("kotlin output", attempt("", "", "kotlin output").output());
        assertEquals("kotlin output", attempt(null, null, "kotlin output").output());
    }

    @Test
    void outputPreservesExistingTrailingNewlineBetweenCompilerOutputs() {
        assertEquals(
                "kotlin output\njavac output\ngroovy output\n",
                attempt("javac output\n", "groovy output\n", "kotlin output").output());
    }

    @Test
    void platformApiWarningPrecedesKotlinAndJavacOutput() {
        TestCompileAttempt warned = attempt(
                        "javac output", "", "kotlin output")
                .withPlatformApiWarning("platform warning");

        assertEquals(
                "platform warning\nkotlin output\njavac output",
                warned.output());
        assertEquals(9, warned.sourceCount());
    }

    private static TestCompileAttempt attempt(
            String javacOutput,
            String groovyOutput,
            String kotlinOutput) {
        return new TestCompileAttempt(
                new JavacResult(3, Path.of("target/test-classes"), javacOutput),
                new JavacResult(2, Path.of("target/test-classes"), groovyOutput),
                new JavacResult(4, Path.of("target/test-classes"), kotlinOutput),
                "full",
                "fallback",
                new CompileDiagnostics(1, 2, 3, 4, 5, 6, 7, 8));
    }
}
