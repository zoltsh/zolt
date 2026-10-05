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

/** Qualifies bounded package-specific Java nullability-annotation rules. */
final class KotlinNullabilityAnnotationsArgumentsTest {
    private static final String JETBRAINS =
            "-Xnullability-annotations=@org.jetbrains.annotations:strict";
    private static final String ANDROID =
            "-Xnullability-annotations=@com.android.annotations:warn";

    @Test
    void mapsDistinctRulesOnlyToKotlincInDeclarationOrderAndPreservesFriendOutput() {
        KotlinCompilerRunner.Options options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(JETBRAINS, ANDROID))
                .withFriendPath(Path.of("target/classes"));

        assertEquals(
                List.of("@org.jetbrains.annotations:strict", "@com.android.annotations:warn"),
                options.nullabilityAnnotations());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(options).arguments().isEmpty());
        List<String> arguments = invocationArguments(options);
        assertEquals(
                List.of(JETBRAINS, ANDROID),
                arguments.stream()
                        .filter(value -> value.startsWith("-Xnullability-annotations="))
                        .toList());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesRulesToTheActiveCompilerLane() {
        KotlinCompilerRunner.Options main = options(
                KotlinCompilationScope.MAIN,
                List.of(JETBRAINS),
                List.of(ANDROID));
        KotlinCompilerRunner.Options test = options(
                KotlinCompilationScope.TEST,
                List.of(JETBRAINS),
                List.of(ANDROID));

        assertEquals(List.of("@org.jetbrains.annotations:strict"), main.nullabilityAnnotations());
        assertEquals(List.of("@com.android.annotations:warn"), test.nullabilityAnnotations());
    }

    @Test
    void rejectsDuplicatePackageRulesEvenWhenTheirModesDiffer() {
        for (List<String> arguments : List.of(
                List.of(JETBRAINS, JETBRAINS),
                List.of(
                        JETBRAINS,
                        "-Xnullability-annotations=@org.jetbrains.annotations:ignore"))) {
            KotlinCompileException duplicate = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.MAIN, arguments, List.of()));

            assertTrue(duplicate.getMessage().contains("[compiler].args"));
            assertTrue(duplicate.getMessage().contains("duplicate compiler argument"));
            assertTrue(duplicate.getMessage().contains("org.jetbrains.annotations"));
        }
    }

    @Test
    void rejectsMalformedPackageAndModeFormsActionably() {
        for (String argument : List.of(
                "-Xnullability-annotations=org.jetbrains.annotations:strict",
                "-Xnullability-annotations=@org..jetbrains:strict",
                "-Xnullability-annotations=@org.jetbrains.annotations:warning",
                "-Xnullability-annotations=@org.jetbrains.annotations:STRICT",
                "-Xnullability-annotations=@org.jetbrains.annotations:strict:warn",
                "-Xnullability-annotations=")) {
            KotlinCompileException invalid = assertThrows(
                    KotlinCompileException.class,
                    () -> options(KotlinCompilationScope.TEST, List.of(), List.of(argument)));

            assertTrue(invalid.getMessage().contains("[compiler.test].args"));
            assertTrue(invalid.getMessage().contains("invalid Kotlin nullability-annotation"));
            assertTrue(invalid.getMessage().contains(argument));
            assertTrue(invalid.getMessage().contains("@package.name"));
        }
    }
}
