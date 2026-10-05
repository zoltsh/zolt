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

/** Qualifies the bounded global Kotlin/JVM JSR-305 severity modes. */
final class KotlinJsr305ArgumentsTest {
    private static final String OPTION = "-Xjsr305=";

    @Test
    void mapsModeOnlyToKotlincAndPreservesItWithFriendOutput() {
        KotlinCompilerRunner.Options options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(OPTION + "strict"))
                .withFriendPath(Path.of("target/classes"));

        assertEquals("strict", options.jsr305Mode());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(OPTION + "strict"), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesModesToTheirCompilerLanes() {
        KotlinCompilerRunner.Options main = options(
                KotlinCompilationScope.MAIN,
                List.of(OPTION + "ignore"),
                List.of(OPTION + "warn"));
        KotlinCompilerRunner.Options test = options(
                KotlinCompilationScope.TEST,
                List.of(OPTION + "ignore"),
                List.of(OPTION + "warn"));

        assertEquals("ignore", main.jsr305Mode());
        assertEquals("warn", test.jsr305Mode());
    }

    @Test
    void acceptsEveryGlobalMode() {
        for (String mode : List.of("ignore", "warn", "strict")) {
            KotlinCompilerRunner.Options options = options(
                    KotlinCompilationScope.MAIN,
                    List.of(OPTION + mode),
                    List.of());
            assertEquals(mode, options.jsr305Mode());
            assertTrue(invocationArguments(options).contains(OPTION + mode));
        }
    }

    @Test
    void rejectsInvalidExtendedAndDuplicateModesActionably() {
        for (String argument : List.of(
                OPTION,
                OPTION + "WARN",
                OPTION + "unknown",
                OPTION + "under-migration:warn",
                OPTION + "@com.example.NullableApi:strict")) {
            KotlinCompileException invalid = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, List.of(argument), List.of()));
            assertTrue(invalid.getMessage().contains("[compiler].args"));
            assertTrue(invalid.getMessage().contains("invalid Kotlin JSR-305"));
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
