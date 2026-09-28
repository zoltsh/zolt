package sh.zolt.build;

import java.nio.file.Path;
import java.util.List;
import sh.zolt.build.discovery.SourceDiscoveryResult;

/** Fail-closed language composition policy applied before compile output reuse or mutation. */
public final class SourceLanguagePolicy {
    private SourceLanguagePolicy() {
    }

    public static void requireMainSupported(SourceDiscoveryResult sources) {
        requireSupported(
                "main",
                "zolt build",
                sources.groovyMainSources(),
                sources.kotlinMainSources());
    }

    public static void requireTestSupported(SourceDiscoveryResult sources) {
        requireSupported(
                "test",
                "zolt test",
                sources.groovyTestSources(),
                sources.kotlinTestSources());
    }

    private static void requireSupported(
            String sourceSet,
            String retryCommand,
            List<Path> groovySources,
            List<Path> kotlinSources) {
        if (!groovySources.isEmpty() && !kotlinSources.isEmpty()) {
            throw BuildException.actionable(
                    "The " + sourceSet
                            + " source set combines Groovy and Kotlin, which Zolt does not support.",
                    "Remove Kotlin from the " + sourceSet
                            + " source set; Kotlin-only compilation is not available yet. Then run `"
                            + retryCommand + "` again.");
        }
        if (!kotlinSources.isEmpty()) {
            throw BuildException.actionable(
                    "Zolt recognized Kotlin " + sourceSet
                            + " sources, but Kotlin compilation is not available yet.",
                    "Remove the Kotlin " + sourceSet
                            + " sources or use another compiler, then run `" + retryCommand + "` again.");
        }
    }
}
