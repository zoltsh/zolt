package sh.zolt.build.compile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
        requireUtf8(compiler.encoding());
        int release = featureVersion(MainCompileOptions.effectiveRelease(config));
        int jdkFeature = jdkStatus.featureVersion().orElseThrow(() -> unsupported(
                "the selected JDK feature version could not be determined",
                "Use a JDK whose `java -version` output Zolt can read."));
        if (release > jdkFeature) {
            throw unsupported(
                    "the effective Java release " + release
                            + " is newer than the selected JDK feature version " + jdkFeature,
                    "Select a Java " + release + " or newer build JDK, or lower [project].java.");
        }
        if (jdkStatus.java().isEmpty() || jdkStatus.javaHome().isEmpty()) {
            throw unsupported(
                    "the selected JDK has no complete Java runtime home",
                    "Install a complete JDK or repair the configured Java toolchain.");
        }
        boolean hostPlatformApi = compiler.mainHostPlatformApi();
        return new KotlinCompilerRunner.Options(
                Integer.toString(release),
                moduleName(config.project().name()),
                hostPlatformApi,
                !hostPlatformApi && jdkFeature >= 9);
    }

    private static void requireUtf8(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return;
        }
        try {
            if (Charset.forName(encoding).equals(StandardCharsets.UTF_8)) {
                return;
            }
        } catch (RuntimeException exception) {
            throw unsupported(
                    "[compiler].encoding is not a recognized UTF-8 encoding",
                    "Set [compiler].encoding to \"UTF-8\" for Kotlin sources.");
        }
        throw unsupported(
                "[compiler].encoding is not UTF-8",
                "Set [compiler].encoding to \"UTF-8\"; the bounded Kotlin compiler reads authored"
                        + " sources as UTF-8.");
    }

    private static int featureVersion(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.startsWith("1.")) {
            normalized = normalized.substring(2);
        }
        int end = 0;
        while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
            end++;
        }
        try {
            int feature = end == 0 ? -1 : Integer.parseInt(normalized.substring(0, end));
            if (feature >= 8) {
                return feature;
            }
        } catch (NumberFormatException ignored) {
            // The actionable failure below covers malformed and out-of-range values alike.
        }
        throw unsupported(
                "the effective Java release `" + value + "` is not a supported feature version",
                "Set [project].java to Java 8 or newer.");
    }

    private static String moduleName(String projectName) {
        String value = projectName == null ? "" : projectName.strip().toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            normalized.append(Character.isLetterOrDigit(character) || character == '_'
                    ? character
                    : '_');
        }
        if (normalized.isEmpty()) {
            normalized.append("main");
        } else if (Character.isDigit(normalized.charAt(0))) {
            normalized.insert(0, "zolt_");
        }
        return normalized.append("_main").toString();
    }

    private static KotlinCompileException unsupported(String reason, String remediation) {
        return new KotlinCompileException(
                "Kotlin main compilation is not supported when " + reason + ". " + remediation);
    }
}
