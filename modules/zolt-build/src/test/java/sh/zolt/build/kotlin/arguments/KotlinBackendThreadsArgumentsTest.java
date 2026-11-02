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

/** Qualifies bounded Kotlin/JVM backend parallelism. */
final class KotlinBackendThreadsArgumentsTest {
    private static final String OPTION = "-Xbackend-threads=";

    @Test
    void mapsCountOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "2"))
                .withFriendPath(Path.of("target/classes"));

        assertEquals("2", options.policy().codeGeneration().backendThreads());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(OPTION + "2"), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesCountsToTheirCompilerLanes() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of(OPTION + "1"),
                List.of(OPTION + "4"));
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of(OPTION + "1"),
                List.of(OPTION + "4"));

        assertEquals("1", main.policy().codeGeneration().backendThreads());
        assertEquals("4", test.policy().codeGeneration().backendThreads());
    }

    @Test
    void acceptsAutomaticAndBoundedExplicitCounts() {
        for (String count : List.of("0", "1", "2", "256")) {
            KotlinCompilerOptions options = options(
                    KotlinCompilationScope.MAIN,
                    List.of(OPTION + count),
                    List.of());
            assertEquals(count, options.policy().codeGeneration().backendThreads());
            assertTrue(invocationArguments(options).contains(OPTION + count));
        }
    }

    @Test
    void rejectsInvalidAndDuplicateCountsActionably() {
        for (String argument : List.of(
                OPTION,
                OPTION + "-1",
                OPTION + "01",
                OPTION + "+1",
                OPTION + "257",
                OPTION + "999999999999999999999",
                OPTION + "many")) {
            KotlinCompileException invalid = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));
            assertTrue(invalid.getMessage().contains("[compiler].args"));
            assertTrue(invalid.getMessage().contains("invalid Kotlin backend-thread"));
            assertTrue(invalid.getMessage().contains(OPTION + "0"));
            assertTrue(invalid.getMessage().contains("1 through 256"));
        }

        KotlinCompileException duplicate = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "1", OPTION + "2")));
        assertTrue(duplicate.getMessage().contains("[compiler.test].args"));
        assertTrue(duplicate.getMessage().contains("duplicate compiler argument"));
    }
}
