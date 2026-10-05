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

/** Qualifies Kotlin and javac preview-class compilation as one mixed-source contract. */
final class KotlinJvmPreviewArgumentsTest {
    private static final String FLAG = "-Xjvm-enable-preview";

    @Test
    void mapsPreviewModeToKotlincAndJavacAndPreservesFriendOutput() {
        KotlinCompilerOptions options = options(
                        KotlinCompilationScope.TEST,
                        List.of(),
                        List.of(FLAG))
                .withFriendPath(Path.of("target/classes"));

        assertTrue(options.jvmPreview());
        assertEquals(
                List.of("--enable-preview"),
                KotlinCompileOptionsPolicy.javacOptions(options).arguments());
        List<String> arguments = invocationArguments(options);
        assertTrue(arguments.contains(FLAG), arguments.toString());
        assertTrue(arguments.contains("-Xfriend-paths=target/classes"), arguments.toString());
    }

    @Test
    void scopesPreviewModeToTheActiveCompilerLane() {
        KotlinCompilerOptions main = options(
                KotlinCompilationScope.MAIN,
                List.of(FLAG),
                List.of());
        KotlinCompilerOptions test = options(
                KotlinCompilationScope.TEST,
                List.of(FLAG),
                List.of());

        assertTrue(main.jvmPreview());
        assertFalse(test.jvmPreview());
        assertTrue(KotlinCompileOptionsPolicy.javacOptions(test).arguments().isEmpty());
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

    @Test
    void rejectsUnsupportedAndMismatchedJavaFeatureReleases() {
        KotlinCompileException oldRelease = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.MAIN,
                        List.of(FLAG),
                        List.of(),
                        "11",
                        "11"));
        assertTrue(oldRelease.getMessage().contains("targets Java 11"));
        assertTrue(oldRelease.getMessage().contains("12 or newer"));

        KotlinCompileException mismatchedJdk = assertThrows(
                KotlinCompileException.class,
                () -> options(
                        KotlinCompilationScope.MAIN,
                        List.of(FLAG),
                        List.of(),
                        "17",
                        "21"));
        assertTrue(mismatchedJdk.getMessage().contains("targets Java 17"));
        assertTrue(mismatchedJdk.getMessage().contains("Java 21 build JDK"));
        assertTrue(mismatchedJdk.getMessage().contains("same Java feature release"));
    }
}
