package sh.zolt.build.compile;

import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;

/** Correctness-first eligibility rules for the bounded Kotlin-only main compiler. */
final class KotlinMainCompilePolicy {
    private KotlinMainCompilePolicy() {
    }

    static KotlinCompilerRunner.Options options(
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            JdkStatus jdkStatus) {
        CompilerSettings compiler = config.compilerSettings();
        if (!sources.groovyMainSources().isEmpty()) {
            throw unsupported(
                    "the main source set also contains Groovy",
                    "Split the Kotlin and Groovy sources into separate members.");
        }
        if (!sources.mainSources().isEmpty()) {
            throw unsupported(
                    "the main source set also contains Java",
                    "Split the Kotlin and Java sources into separate members until Kotlin/Java joint"
                            + " compilation is supported.");
        }
        if (!classpaths.processor().entries().isEmpty()) {
            throw unsupported(
                    "annotation processors are configured",
                    "Remove [dependencies.processor] or keep this member Java-only.");
        }
        if (!compiler.args().isEmpty()) {
            throw unsupported(
                    "[compiler].args is not empty",
                    "Remove the custom javac arguments or keep this member Java-only; Zolt does not"
                            + " forward javac flags to kotlinc.");
        }
        if (!config.workspaceApiDependencies().isEmpty()
                || !config.workspaceDependencies().isEmpty()) {
            throw unsupported(
                    "compile-scoped workspace dependencies are configured",
                    "Keep the Kotlin preview member independent of workspace compile dependencies"
                            + " until Kotlin module metadata participates in workspace ABI keys.");
        }
        return KotlinCompileOptionsPolicy.options(
                config,
                jdkStatus,
                KotlinCompilationScope.MAIN);
    }

    private static KotlinCompileException unsupported(String reason, String remediation) {
        return new KotlinCompileException(
                "Kotlin main compilation is not supported when " + reason + ". " + remediation);
    }
}
