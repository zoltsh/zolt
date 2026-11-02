package sh.zolt.build.compile.kotlin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.KotlinCompilationScope;

final class KotlinCompilerDiagnosticPolicyTest {
    @Test
    void rejectsTheSuccessfulUnsupportedFlagDiagnostic() {
        String output = """
                warning: flag is not supported by this version of the compiler:
                -Xannotation-default-target=param-property
                """;

        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinCompilerDiagnosticPolicy.requireNoUnsupportedOption(
                        output, KotlinCompilationScope.MAIN));

        assertTrue(failure.getMessage().contains("returning success"), failure.getMessage());
        assertTrue(failure.getMessage().contains("-Xannotation-default-target=param-property"),
                failure.getMessage());
    }

    @Test
    void reportsTheTestCompilationScope() {
        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> KotlinCompilerDiagnosticPolicy.requireNoUnsupportedOption(
                        "w: warning: option is not supported by this version of compiler: -Xfuture",
                        KotlinCompilationScope.TEST));

        assertTrue(failure.getMessage().contains("Kotlin test compilation"), failure.getMessage());
    }

    @Test
    void preservesOrdinaryWarnings() {
        assertDoesNotThrow(() -> KotlinCompilerDiagnosticPolicy.requireNoUnsupportedOption(
                "warning: variable 'unused' is never used",
                KotlinCompilationScope.MAIN));
    }
}
