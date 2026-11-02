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

/** Qualifies the bounded Kotlin/JVM assertion code-generation modes. */
final class KotlinAssertionModeArgumentsTest {
    private static final String OPTION = "-Xassertions=";

    @Test
    void mapsModeOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "jvm"))
                .withFriendPath(Path.of("target/classes"));

        assertEquals("jvm", options.policy().codeGeneration().assertionMode().argumentValue());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(OPTION + "jvm"), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesModesToTheirCompilerLanes() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of(OPTION + "always-enable"),
                List.of(OPTION + "always-disable"));
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of(OPTION + "always-enable"),
                List.of(OPTION + "always-disable"));

        assertEquals("always-enable", main.policy().codeGeneration().assertionMode().argumentValue());
        assertEquals("always-disable", test.policy().codeGeneration().assertionMode().argumentValue());
    }

    @Test
    void acceptsEveryCompilerMode() {
        for (String mode : List.of("always-enable", "always-disable", "jvm", "legacy")) {
            KotlinCompilerOptions options = options(
                    KotlinCompilationScope.MAIN,
                    List.of(OPTION + mode),
                    List.of());
            assertEquals(mode, options.policy().codeGeneration().assertionMode().argumentValue());
            assertTrue(invocationArguments(options).contains(OPTION + mode));
        }
    }

    @Test
    void rejectsInvalidAndDuplicateModesActionably() {
        for (String argument : List.of(OPTION, OPTION + "JVM", OPTION + "unknown")) {
            KotlinCompileException invalid = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));
            assertTrue(invalid.getMessage().contains("[compiler].args"));
            assertTrue(invalid.getMessage().contains("invalid Kotlin assertion"));
            assertTrue(invalid.getMessage().contains(OPTION + "always-enable"));
        }

        KotlinCompileException duplicate = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "jvm", OPTION + "legacy")));
        assertTrue(duplicate.getMessage().contains("[compiler.test].args"));
        assertTrue(duplicate.getMessage().contains("duplicate compiler argument"));
    }
}
