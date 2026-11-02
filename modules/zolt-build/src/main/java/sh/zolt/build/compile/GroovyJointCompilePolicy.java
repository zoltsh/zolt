package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.List;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;

/** Correctness-first eligibility rules for main-source Groovy joint compilation. */
final class GroovyJointCompilePolicy {
    private GroovyJointCompilePolicy() {
    }

    static GroovyCompilerRunner.JointOptions options(
            ProjectConfig config,
            List<Path> sources,
            ClasspathSet classpaths,
            JdkStatus jdkStatus) {
        CompilerSettings compiler = config.compilerSettings();
        if (!classpaths.processor().entries().isEmpty()) {
            throw unsupported(
                    "annotation processors are configured",
                    "Remove [dependencies.processor] from this Groovy member or generate the sources"
                            + " in a separate Java-only member.");
        }
        if (!compiler.args().isEmpty()) {
            throw unsupported(
                    "[compiler].args is not empty",
                    "Remove the custom javac arguments or keep this member Java-only until joint"
                            + " compiler-argument forwarding is supported.");
        }
        if (CompilerPlatformApi.isModularSourceSet(sources)) {
            throw unsupported(
                    "the main source set contains module-info.java",
                    "Remove module-info.java or keep this member Java-only until modular Groovy joint"
                            + " compilation is supported.");
        }
        if (!config.workspaceApiDependencies().isEmpty()
                || !config.workspaceDependencies().isEmpty()) {
            throw unsupported(
                    "compile-scoped workspace dependencies are configured",
                    "Keep the Groovy member independent of workspace compile dependencies until Zolt"
                            + " fingerprints their full content for Groovy AST-transform safety.");
        }

        String release = MainCompileOptions.effectiveRelease(config);
        boolean hostMode = compiler.mainHostPlatformApi() && !release.isBlank();
        if (!hostMode) {
            int releaseFeature = featureVersion(release, "effective Java release");
            int jdkFeature = jdkStatus.featureVersion().orElseThrow(() -> unsupported(
                    "the selected JDK feature version could not be determined",
                    "Use a JDK whose `java -version` output Zolt can read, or opt into"
                            + " [compiler] jdkApi = \"host\" explicitly."));
            if (jdkFeature != releaseFeature) {
                throw unsupported(
                        "the selected JDK feature version " + jdkFeature
                                + " differs from the effective Java release " + releaseFeature,
                        "Select a Java " + releaseFeature
                                + " build JDK, or explicitly opt into [compiler] jdkApi = \"host\"."
                                + " Groovy source resolution otherwise sees host-JDK APIs that"
                                + " --release only hides from the embedded javac invocation.");
            }
        }
        if (jdkStatus.java().isEmpty()) {
            throw unsupported(
                    "the selected JDK has no java executable",
                    "Install a complete JDK or repair the configured Java toolchain.");
        }
        return new GroovyCompilerRunner.JointOptions(release, compiler.encoding(), hostMode);
    }

    private static int featureVersion(String value, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("1.")) {
            normalized = normalized.substring(2);
        }
        int end = 0;
        while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
            end++;
        }
        if (end == 0) {
            throw unsupported(
                    label + " `" + value + "` is not a Java feature version",
                    "Set [project].java to a numeric Java feature release.");
        }
        try {
            return Integer.parseInt(normalized.substring(0, end));
        } catch (NumberFormatException exception) {
            throw unsupported(
                    label + " `" + value + "` is not a Java feature version",
                    "Set [project].java to a numeric Java feature release.");
        }
    }

    private static GroovyCompileException unsupported(String reason, String remediation) {
        return new GroovyCompileException(
                "Groovy main joint compilation is not supported when " + reason + ". " + remediation);
    }
}
