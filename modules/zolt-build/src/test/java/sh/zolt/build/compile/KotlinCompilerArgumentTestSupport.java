package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import sh.zolt.build.compile.kotlin.KotlinCompilerInvocationArguments;
import sh.zolt.classpath.Classpath;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;

/** Narrow bridge for compiler-argument tests that live outside the compile implementation package. */
public final class KotlinCompilerArgumentTestSupport {
    private KotlinCompilerArgumentTestSupport() {
    }

    public static KotlinCompilerOptions options(
            KotlinCompilationScope scope,
            List<String> mainArguments,
            List<String> testArguments) {
        return options(scope, mainArguments, testArguments, "", "21");
    }

    public static KotlinCompilerOptions options(
            KotlinCompilationScope scope,
            List<String> mainArguments,
            List<String> testArguments,
            String release,
            String jdkFeature) {
        ProjectConfig config = KotlinMainCompilePolicyTest.config(
                new CompilerSettings(null, null, release, "", mainArguments, testArguments),
                Map.of(),
                Map.of(),
                "demo");
        return KotlinCompileOptionsPolicy.options(
                config,
                KotlinMainCompilePolicyTest.jdkStatus(jdkFeature + ".0.11", jdkFeature),
                scope);
    }

    public static List<String> invocationArguments(KotlinCompilerOptions options) {
        return KotlinCompilerInvocationArguments.build(
                Path.of("/jdk"),
                List.of(Path.of("src/Test.kt")),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                Path.of("target/test-classes"),
                options,
                ":");
    }
}
