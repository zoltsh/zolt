package sh.zolt.build.compile;

import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;

/** Correctness-first eligibility rules for the bounded Kotlin/JVM main compiler. */
final class KotlinMainCompilePolicy {
    private KotlinMainCompilePolicy() {
    }

    static KotlinCompilerOptions options(
            ProjectConfig config,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            JdkStatus jdkStatus) {
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
        if ((!sources.mainSources().isEmpty() || !classpaths.processor().entries().isEmpty())
                && jdkStatus.javac().isEmpty()) {
            throw unsupported(
                    "Java composition or annotation processing needs javac but the selected JDK has no javac executable",
                    "Install a complete JDK or repair the configured Java toolchain.");
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
