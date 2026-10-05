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
import sh.zolt.build.compile.KotlinCompilerOptions;

/** Qualifies the bounded Kotlin/JVM JSpecify nullness-severity modes. */
final class KotlinJSpecifyAnnotationsArgumentsTest {
    private static final String OPTION = "-Xjspecify-annotations=";

    @Test
    void mapsModeOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "strict"))
                .withFriendPath(Path.of("target/classes"));

        assertEquals("strict", options.jspecifyAnnotationsMode());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(OPTION + "strict"), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesModesToTheirCompilerLanes() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of(OPTION + "ignore"),
                List.of(OPTION + "warn"));
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of(OPTION + "ignore"),
                List.of(OPTION + "warn"));

        assertEquals("ignore", main.jspecifyAnnotationsMode());
        assertEquals("warn", test.jspecifyAnnotationsMode());
    }

    @Test
    void acceptsEveryCompilerMode() {
        for (String mode : List.of("ignore", "warn", "strict")) {
            KotlinCompilerOptions options = options(
                    KotlinCompilationScope.MAIN,
                    List.of(OPTION + mode),
                    List.of());
            assertEquals(mode, options.jspecifyAnnotationsMode());
            assertTrue(invocationArguments(options).contains(OPTION + mode));
        }
    }

    @Test
    void rejectsInvalidAndDuplicateModesActionably() {
        for (String argument : List.of(OPTION, OPTION + "WARN", OPTION + "unknown")) {
            KotlinCompileException invalid = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));
            assertTrue(invalid.getMessage().contains("[compiler].args"));
            assertTrue(invalid.getMessage().contains("invalid Kotlin JSpecify-annotation"));
            assertTrue(invalid.getMessage().contains(OPTION + "ignore"));
        }

        KotlinCompileException duplicate = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "warn", OPTION + "strict")));
        assertTrue(duplicate.getMessage().contains("[compiler.test].args"));
        assertTrue(duplicate.getMessage().contains("duplicate compiler argument"));
    }
}
