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

/** Qualifies numbered inline-scope markers in Kotlin debug metadata. */
final class KotlinInlineScopeNumbersArgumentsTest {
    private static final String FLAG = "-Xuse-inline-scopes-numbers";

    @Test
    void mapsInlineScopeNumbersOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(FLAG))
                .withFriendPath(Path.of("target/classes"));

        assertTrue(options.useInlineScopesNumbers());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(FLAG), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesInlineScopeNumbersToTheActiveCompilerLane() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of(FLAG),
                List.of());
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of(FLAG),
                List.of());

        assertTrue(main.useInlineScopesNumbers());
        assertFalse(test.useInlineScopesNumbers());
    }

    @Test
    void rejectsDuplicateAndAssignedFormsActionably() {
        KotlinCompileException duplicate = assertThrows(
                KotlinCompileException.class,
                () -> options(KotlinCompilationScope.TEST, List.of(), List.of(FLAG, FLAG)));
        assertTrue(duplicate.getMessage().contains("[compiler.test].args"));
        assertTrue(duplicate.getMessage().contains("duplicate compiler argument"));

        KotlinCompileException assigned = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.MAIN,
                        List.of(FLAG + "=true"),
                        List.of()));
        assertTrue(assigned.getMessage().contains("[compiler].args"));
        assertTrue(assigned.getMessage().contains("unsupported compiler argument"));
        assertTrue(assigned.getMessage().contains(FLAG));
    }
}
