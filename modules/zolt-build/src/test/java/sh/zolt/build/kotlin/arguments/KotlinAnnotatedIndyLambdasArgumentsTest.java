package sh.zolt.build.kotlin.arguments;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** Qualifies bounded invokedynamic generation for annotated Kotlin lambdas. */
final class KotlinAnnotatedIndyLambdasArgumentsTest {
    private static final String FLAG = "-Xindy-allow-annotated-lambdas";
    private static final String INDY = "-Xlambdas=indy";

    @Test
    void mapsAnnotatedIndyLambdasOnlyToKotlincAndPreservesThemWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(INDY, FLAG))
                .withFriendPath(Path.of("target/classes"));

        assertTrue(options.policy().codeGeneration().indyAllowAnnotatedLambdas());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(INDY), arguments.toString());
        assertTrue(arguments.contains(FLAG), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesAnnotatedIndyLambdasToTheActiveCompilerLane() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of(INDY, FLAG),
                List.of());
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of(INDY, FLAG),
                List.of());

        assertTrue(main.policy().codeGeneration().indyAllowAnnotatedLambdas());
        assertFalse(test.policy().codeGeneration().indyAllowAnnotatedLambdas());
    }

    @Test
    void rejectsMissingAndClassLambdaModesActionably() {
        KotlinCompileException missing = assertThrows(
                KotlinCompileException.class,
                () -> options(KotlinCompilationScope.MAIN, List.of(FLAG), List.of()));
        assertTrue(missing.getMessage().contains("[compiler].args"));
        assertTrue(missing.getMessage().contains(FLAG));
        assertTrue(missing.getMessage().contains(INDY));

        KotlinCompileException classMode = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of("-Xlambdas=class", FLAG)));
        assertTrue(classMode.getMessage().contains("[compiler.test].args"));
        assertTrue(classMode.getMessage().contains(INDY));
    }

    @Test
    void rejectsDuplicateAndAssignedFormsActionably() {
        KotlinCompileException duplicate = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(INDY, FLAG, FLAG)));
        assertTrue(duplicate.getMessage().contains("duplicate compiler argument"));

        KotlinCompileException assigned = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.MAIN,
                        List.of(INDY, FLAG + "=true"),
                        List.of()));
        assertTrue(assigned.getMessage().contains("unsupported compiler argument"));
        assertTrue(assigned.getMessage().contains(FLAG));
    }
}
