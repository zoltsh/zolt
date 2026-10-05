package sh.zolt.build.kotlin.arguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.build.compile.KotlinCompilerArgumentTestSupport.invocationArguments;
import static sh.zolt.build.compile.KotlinCompilerArgumentTestSupport.options;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompileOptionsPolicy;
import sh.zolt.build.compile.KotlinCompilerRunner;

/** Qualifies repeatable, kotlinc-only diagnostic warning levels. */
final class KotlinWarningLevelArgumentsTest {
    @Test
    void mapsWarningLevelsOnlyToKotlincAndPreservesThemWithFriendOutput() {
        KotlinCompilerRunner.Options options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(
                                "-Xwarning-level=DEPRECATION:disabled",
                                "-Xwarning-level=UNUSED_VARIABLE:error"))
                .withFriendPath(Path.of("target/classes"));
        assertEquals(
                List.of("DEPRECATION:disabled", "UNUSED_VARIABLE:error"),
                options.warningLevels());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains("-Xwarning-level=DEPRECATION:disabled"), arguments.toString());
        assertTrue(arguments.contains("-Xwarning-level=UNUSED_VARIABLE:error"), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void acceptsEverySeverityAndScopesItToTheActiveCompilerLane() {
        for (String severity : List.of("error", "warning", "disabled")) {
            KotlinCompilerRunner.Options main = options(
                    KotlinCompilationScope.MAIN,
                    List.of("-Xwarning-level=DEPRECATION:" + severity),
                    List.of("-Xwarning-level=UNUSED_VARIABLE:disabled"));
            assertEquals(List.of("DEPRECATION:" + severity), main.warningLevels());
        }
        KotlinCompilerRunner.Options test = options(
                KotlinCompilationScope.TEST,
                List.of("-Xwarning-level=DEPRECATION:error"),
                List.of("-Xwarning-level=UNUSED_VARIABLE:warning"));
        assertEquals(List.of("UNUSED_VARIABLE:warning"), test.warningLevels());
    }

    @Test
    void composesWithModuleWideWarningPolicies() {
        KotlinCompilerRunner.Options suppressed = options(
                KotlinCompilationScope.MAIN,
                List.of("-nowarn", "-Xwarning-level=DEPRECATION:warning"),
                List.of());
        assertEquals(List.of("-nowarn"), KotlinCompileOptionsPolicy.javacOptions(suppressed).arguments());
        KotlinCompilerRunner.Options enforced = options(
                KotlinCompilationScope.MAIN,
                List.of("-Werror", "-Xwarning-level=DEPRECATION:warning"),
                List.of());
        assertEquals(List.of("-Werror"), KotlinCompileOptionsPolicy.javacOptions(enforced).arguments());
        KotlinCompilerRunner.Options expanded = options(
                KotlinCompilationScope.MAIN,
                List.of("-Wextra", "-Xwarning-level=REDUNDANT_VISIBILITY_MODIFIER:disabled"),
                List.of());
        assertTrue(expanded.extraWarnings());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(expanded).arguments().isEmpty());
    }

    @Test
    void rejectsMalformedAndDuplicateDiagnosticRulesActionably() {
        for (String argument : List.of(
                "-Xwarning-level=",
                "-Xwarning-level=deprecation:warning",
                "-Xwarning-level=DEPRECATION:warn",
                "-Xwarning-level=DEPRECATION")) {
            KotlinCompileException failure = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));
            assertTrue(failure.getMessage().contains("[compiler].args"));
            assertTrue(failure.getMessage().contains("invalid Kotlin warning-level argument"));
            assertTrue(failure.getMessage().contains("DIAGNOSTIC_NAME:error"));
        }
        KotlinCompileException failure = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(
                                "-Xwarning-level=DEPRECATION:warning",
                                "-Xwarning-level=DEPRECATION:disabled")));
        assertTrue(failure.getMessage().contains("[compiler.test].args"));
        assertTrue(failure.getMessage().contains("duplicate compiler argument"));
        assertTrue(failure.getMessage().contains("DEPRECATION:disabled"));
    }
}
