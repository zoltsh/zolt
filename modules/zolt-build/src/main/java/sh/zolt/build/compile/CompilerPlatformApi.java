package sh.zolt.build.compile;

import sh.zolt.build.BuildException;
import sh.zolt.doctor.JdkStatus;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves the {@code [compiler] jdkApi} / {@code [compiler.test] jdkApi} opt-in into the
 * concrete javac behavior for a source set.
 *
 * <p>Default ({@code "release"}) keeps {@code javac --release N}: the platform API is pinned to Java
 * N via ct.sym, so the build is reproducible across build JDKs (Principle 7). The opt-in
 * ({@code "host"}) switches to {@code -source N -target N}, compiling against the running JDK's
 * platform API. Host mode is the single explicit, audited exception to reproducibility: it is
 * rejected for modular source sets and it emits a loud build-time determinism warning naming the
 * build JDK feature version.
 */
public final class CompilerPlatformApi {
    private CompilerPlatformApi() {
    }

    /**
     * Rejects host mode when the source set is modular. {@code -source/-target} targeting a pre-9
     * level is contradictory with the Java module system (Java 9+), so this fails fast with an
     * actionable message rather than producing a confusing javac error.
     */
    public static void rejectModularHost(boolean hostMode, List<Path> sources, String sourceSet) {
        if (!hostMode || !isModularSourceSet(sources)) {
            return;
        }
        throw BuildException.actionable(
                "The " + sourceSet + " source set declares a module (module-info.java) but"
                        + " " + hostKeyFor(sourceSet) + " = \"host\" is set.",
                "Host platform-API mode compiles with -source/-target, which the Java module system"
                        + " (Java 9+) does not support at a pre-9 level. Remove the host opt-in and use"
                        + " the default --release mode, raise [project].java, or split the"
                        + " newer-API code into a multi-release JAR.");
    }

    /**
     * The build-time determinism warning for a host-mode source set, or empty when the source set
     * is not in host mode. The warning names the build JDK feature version because host mode makes
     * the accepted platform API depend on it, forfeiting cross-JDK reproducibility.
     */
    public static String determinismWarning(boolean hostMode, String sourceSet, JdkStatus jdkStatus) {
        if (!hostMode) {
            return "";
        }
        String buildJdk = jdkStatus.featureVersion()
                .map(String::valueOf)
                .orElse(jdkStatus.version().orElse("unknown"));
        return "warning: "
                + hostKeyFor(sourceSet)
                + " = \"host\" compiles " + sourceSet + " sources against the build JDK's platform"
                + " API (build JDK feature version " + buildJdk + "), not the ct.sym-pinned"
                + " --release surface. This forfeits Zolt's cross-JDK reproducibility guarantee:"
                + " the same sources may accept or reject differently on another JDK. Prefer raising"
                + " [project].java or a multi-release JAR when reproducibility matters.";
    }

    /** Whether a source set contains a {@code module-info.java}, i.e. it declares a Java module. */
    public static boolean isModularSourceSet(List<Path> sources) {
        if (sources == null) {
            return false;
        }
        for (Path source : sources) {
            Path fileName = source.getFileName();
            if (fileName != null && "module-info.java".equals(fileName.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String hostKeyFor(String sourceSet) {
        return "test".equals(sourceSet) ? "[compiler.test] jdkApi" : "[compiler] jdkApi";
    }
}
