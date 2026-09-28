package sh.zolt.build.testruntime.compile;

import java.nio.file.Path;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.build.compile.KotlinCompileOptionsPolicy;
import sh.zolt.build.compile.KotlinCompilerRunner;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;

/** Correctness-first eligibility rules for the bounded Kotlin-only test compiler. */
final class KotlinTestCompilePolicy {
    private KotlinTestCompilePolicy() {
    }

    static KotlinCompilerRunner.Options options(
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            JdkStatus jdkStatus,
            Path mainOutputDirectory) {
        CompilerSettings compiler = config.compilerSettings();
        if (!sources.groovyTestSources().isEmpty()) {
            throw unsupported(
                    "the test source set also contains Groovy",
                    "Split the Kotlin and Groovy tests into separate members.");
        }
        if (!sources.testSources().isEmpty()) {
            throw unsupported(
                    "the test source set also contains Java",
                    "Use a Kotlin-only test source set until Kotlin/Java joint test compilation is supported.");
        }
        if (!classpaths.testProcessor().entries().isEmpty()) {
            throw unsupported(
                    "test annotation processors are configured",
                    "Remove [dependencies.test-processor] or keep the test source set Java-only.");
        }
        if (!compiler.testArgs().isEmpty()) {
            throw unsupported(
                    "[compiler].testArgs is not empty",
                    "Remove the custom javac test arguments or keep the test source set Java-only; "
                            + "Zolt does not forward javac flags to kotlinc.");
        }
        if (config.frameworkSettings().quarkus().enabled()) {
            throw unsupported(
                    "Quarkus is enabled",
                    "Keep Quarkus tests Java-only until the Quarkus workspace model represents"
                            + " explicit Kotlin test roots.");
        }
        KotlinCompilerRunner.Options options = KotlinCompileOptionsPolicy.options(
                config,
                jdkStatus,
                KotlinCompilationScope.TEST);
        if (sources.kotlinMainSources().isEmpty()) {
            return options;
        }
        if (mainOutputDirectory == null) {
            throw unsupported(
                    "its own Kotlin main output directory is unavailable",
                    "Build the member's Kotlin main sources before compiling its tests.");
        }
        return options.withFriendPath(mainOutputDirectory);
    }

    private static KotlinCompileException unsupported(String reason, String remediation) {
        return new KotlinCompileException(
                "Kotlin test compilation is not supported when " + reason + ". " + remediation);
    }
}
