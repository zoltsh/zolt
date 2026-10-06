package sh.zolt.build.kotlin.arguments;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** Qualifies Kotlin ABI-stability marking and intentional unstable dependency use. */
final class KotlinAbiStabilityArgumentsTest {
    private static final String MODE = "-Xabi-stability=";
    private static final String ALLOW = "-Xallow-unstable-dependencies";

    @Test
    void mapsAbiPolicyOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(MODE + "unstable", ALLOW))
                .withFriendPath(Path.of("target/classes"));

        assertEquals("unstable", options.policy().metadata().abiStabilityMode());
        assertTrue(options.policy().metadata().allowUnstableDependencies());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(MODE + "unstable"), arguments.toString());
        assertTrue(arguments.contains(ALLOW), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesAbiPolicyToTheActiveCompilerLane() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of(MODE + "unstable", ALLOW),
                List.of(MODE + "stable"));
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of(MODE + "unstable", ALLOW),
                List.of(MODE + "stable"));

        assertEquals("unstable", main.policy().metadata().abiStabilityMode());
        assertTrue(main.policy().metadata().allowUnstableDependencies());
        assertEquals("stable", test.policy().metadata().abiStabilityMode());
        assertFalse(test.policy().metadata().allowUnstableDependencies());
    }

    @Test
    void acceptsEveryAbiStabilityMode() {
        for (String mode : List.of("stable", "unstable")) {
            KotlinCompilerOptions options = options(
                    KotlinCompilationScope.MAIN,
                    List.of(MODE + mode),
                    List.of());
            assertEquals(mode, options.policy().metadata().abiStabilityMode());
            assertTrue(invocationArguments(options).contains(MODE + mode));
        }
    }

    @Test
    void rejectsInvalidDuplicateAndAssignedFormsActionably() {
        for (String argument : List.of(MODE, MODE + "STABLE", MODE + "unknown")) {
            KotlinCompileException invalid = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));
            assertTrue(invalid.getMessage().contains("[compiler].args"));
            assertTrue(invalid.getMessage().contains("invalid Kotlin ABI-stability"));
            assertTrue(invalid.getMessage().contains(MODE + "stable"));
        }

        KotlinCompileException duplicateMode = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(MODE + "stable", MODE + "unstable")));
        assertTrue(duplicateMode.getMessage().contains("duplicate compiler argument"));

        KotlinCompileException duplicateAllow = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(ALLOW, ALLOW)));
        assertTrue(duplicateAllow.getMessage().contains("duplicate compiler argument"));

        KotlinCompileException assignedAllow = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.MAIN,
                        List.of(ALLOW + "=true"),
                        List.of()));
        assertTrue(assignedAllow.getMessage().contains("unsupported compiler argument"));
    }
}
