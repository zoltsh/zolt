package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import sh.zolt.classpath.Classpath;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;

/** Narrow bridge for compiler-argument tests that live outside the compile implementation package. */
public final class KotlinCompilerArgumentTestSupport {
    private KotlinCompilerArgumentTestSupport() {
    }

    public static KotlinCompilerRunner.Options options(
            KotlinCompilationScope scope,
            List<String> mainArguments,
            List<String> testArguments) {
        ProjectConfig config = KotlinMainCompilePolicyTest.config(
                new CompilerSettings(null, null, "", "", mainArguments, testArguments),
                Map.of(),
                Map.of(),
                "demo");
        return KotlinCompileOptionsPolicy.options(
                config,
                KotlinMainCompilePolicyTest.jdkStatus("21.0.11", "21"),
                scope);
    }

    public static List<String> invocationArguments(KotlinCompilerRunner.Options options) {
        return KotlinCompilerInvocationArguments.build(
                Path.of("/jdk"),
                List.of(Path.of("src/Test.kt")),
                new Classpath(List.of(Path.of("stdlib.jar"))),
                Path.of("target/test-classes"),
                options,
                ":");
    }
}
