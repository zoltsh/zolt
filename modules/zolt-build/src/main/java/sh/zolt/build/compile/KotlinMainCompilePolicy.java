package sh.zolt.build.compile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProducesLane;

/** Correctness-first eligibility rules for the bounded Kotlin/JVM main compiler. */
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
        if (CompilerPlatformApi.isModularSourceSet(sources.mainSources())) {
            throw unsupported(
                    "the main source set contains module-info.java",
                    "Remove module-info.java or keep this member Java-only until modular Kotlin/Java"
                            + " joint compilation is supported.");
        }
        if (config.build().generatedMainSources().stream()
                .anyMatch(KotlinMainCompilePolicy::producesJavaSources)) {
            throw unsupported(
                    "generated main sources are configured",
                    "Move generated Java into a separate member or keep this member Java-only until"
                            + " generated-source ownership is qualified for Kotlin/Java joint compilation.");
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
        if (!sources.mainSources().isEmpty() && jdkStatus.javac().isEmpty()) {
            throw unsupported(
                    "the selected JDK has no javac executable",
                    "Install a complete JDK or repair the configured Java toolchain.");
        }
        return KotlinCompileOptionsPolicy.options(
                config,
                jdkStatus,
                KotlinCompilationScope.MAIN);
    }

    static JavacOptions javacOptions(KotlinCompilerRunner.Options kotlinOptions) {
        return new JavacOptions(
                kotlinOptions.release(),
                StandardCharsets.UTF_8.name(),
                List.of(),
                List.of(),
                kotlinOptions.hostPlatformApi(),
                kotlinOptions.useJdkRelease());
    }

    private static boolean producesJavaSources(GeneratedSourceStep step) {
        return step.kind() != GeneratedSourceKind.EXEC
                || step.exec().produces() == ProducesLane.JAVA_SOURCES
                || step.exec().produces() == ProducesLane.TEST_SOURCES;
    }

    private static KotlinCompileException unsupported(String reason, String remediation) {
        return new KotlinCompileException(
                "Kotlin main compilation is not supported when " + reason + ". " + remediation);
    }
}
