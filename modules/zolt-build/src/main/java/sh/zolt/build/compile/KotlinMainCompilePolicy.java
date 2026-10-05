package sh.zolt.build.compile;

import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProducesLane;

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
        if (config.build().generatedMainSources().stream()
                .anyMatch(KotlinMainCompilePolicy::producesOwnedJavaSources)) {
            throw unsupported(
                    "owned Java main-source generation is configured",
                    "Use language = \"kotlin\" for an OpenAPI, Protobuf, or exec step that emits Kotlin, use kind ="
                            + " \"declared-root\" for a pre-generated Java or Kotlin root, move generated Java"
                            + " into a separate member, or keep this member Java-only until generator ownership"
                            + " is qualified for Kotlin/Java joint compilation.");
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

    private static boolean producesOwnedJavaSources(GeneratedSourceStep step) {
        return switch (step.kind()) {
            case DECLARED_ROOT -> false;
            case OPENAPI -> !"kotlin".equals(step.language());
            case PROTOBUF -> !"kotlin".equals(step.language());
            case EXEC -> "java".equals(step.language())
                    && (step.exec().produces() == ProducesLane.JAVA_SOURCES
                            || step.exec().produces() == ProducesLane.TEST_SOURCES);
        };
    }

    private static KotlinCompileException unsupported(String reason, String remediation) {
        return new KotlinCompileException(
                "Kotlin main compilation is not supported when " + reason + ". " + remediation);
    }
}
