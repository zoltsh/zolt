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

/** Qualifies the bounded Kotlin/JVM lambda code-generation modes. */
final class KotlinLambdaModeArgumentsTest {
    private static final String OPTION = "-Xlambdas=";

    @Test
    void mapsModeOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "class"))
                .withFriendPath(Path.of("target/classes"));

        assertEquals("class", options.policy().codeGeneration().lambdaMode());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(OPTION + "class"), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesModesToTheirCompilerLanes() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of(OPTION + "class"),
                List.of(OPTION + "indy"));
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of(OPTION + "class"),
                List.of(OPTION + "indy"));

        assertEquals("class", main.policy().codeGeneration().lambdaMode());
        assertEquals("indy", test.policy().codeGeneration().lambdaMode());
    }

    @Test
    void acceptsBothCompilerModes() {
        for (String mode : List.of("class", "indy")) {
            KotlinCompilerOptions options = options(
                    KotlinCompilationScope.MAIN,
                    List.of(OPTION + mode),
                    List.of());
            assertEquals(mode, options.policy().codeGeneration().lambdaMode());
            assertTrue(invocationArguments(options).contains(OPTION + mode));
        }
    }

    @Test
    void rejectsInvalidAndDuplicateModesActionably() {
        for (String argument : List.of(OPTION, OPTION + "CLASS", OPTION + "unknown")) {
            KotlinCompileException invalid = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));
            assertTrue(invalid.getMessage().contains("[compiler].args"));
            assertTrue(invalid.getMessage().contains("invalid Kotlin lambda-generation"));
            assertTrue(invalid.getMessage().contains(OPTION + "class"));
        }

        KotlinCompileException duplicate = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "class", OPTION + "indy")));
        assertTrue(duplicate.getMessage().contains("[compiler.test].args"));
        assertTrue(duplicate.getMessage().contains("duplicate compiler argument"));
    }
}
