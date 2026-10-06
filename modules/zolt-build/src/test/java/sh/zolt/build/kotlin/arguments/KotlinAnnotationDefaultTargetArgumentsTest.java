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

/** Qualifies the bounded Kotlin annotation default-target modes. */
final class KotlinAnnotationDefaultTargetArgumentsTest {
    private static final String OPTION = "-Xannotation-default-target=";

    @Test
    void mapsModeOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "param-property"))
                .withFriendPath(Path.of("target/classes"));

        assertEquals("param-property", options.policy().language().annotationDefaultTargetMode());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(OPTION + "param-property"), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesModesToTheirCompilerLanes() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of(OPTION + "first-only"),
                List.of(OPTION + "param-property"));
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of(OPTION + "first-only"),
                List.of(OPTION + "param-property"));

        assertEquals("first-only", main.policy().language().annotationDefaultTargetMode());
        assertEquals("param-property", test.policy().language().annotationDefaultTargetMode());
    }

    @Test
    void acceptsEveryCompilerMode() {
        for (String mode : List.of("first-only", "first-only-warn", "param-property")) {
            KotlinCompilerOptions options = options(
                    KotlinCompilationScope.MAIN,
                    List.of(OPTION + mode),
                    List.of());
            assertEquals(mode, options.policy().language().annotationDefaultTargetMode());
            assertTrue(invocationArguments(options).contains(OPTION + mode));
        }
    }

    @Test
    void rejectsInvalidAndDuplicateModesActionably() {
        for (String argument : List.of(OPTION, OPTION + "PARAM-PROPERTY", OPTION + "unknown")) {
            KotlinCompileException invalid = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));
            assertTrue(invalid.getMessage().contains("[compiler].args"));
            assertTrue(invalid.getMessage().contains("invalid Kotlin annotation-default-target"));
            assertTrue(invalid.getMessage().contains(OPTION + "param-property"));
        }

        KotlinCompileException duplicate = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "first-only", OPTION + "param-property")));
        assertTrue(duplicate.getMessage().contains("[compiler.test].args"));
        assertTrue(duplicate.getMessage().contains("duplicate compiler argument"));
    }
}
