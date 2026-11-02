package sh.zolt.build;

import sh.zolt.build.discovery.SourceDiscoveryResult;

/** Fail-closed language composition policy applied before compile output reuse or mutation. */
public final class SourceLanguagePolicy {
    private SourceLanguagePolicy() {
    }

    public static void requireMainSupported(SourceDiscoveryResult sources) {
        if (!sources.groovyMainSources().isEmpty() && !sources.kotlinMainSources().isEmpty()) {
            throw BuildException.actionable(
                    "The main source set combines Groovy and Kotlin, which Zolt does not support.",
                    "Use either Groovy or Kotlin for the main source set, then run `zolt build` again.");
        }
    }

    public static void requireTestSupported(SourceDiscoveryResult sources) {
        if (!sources.groovyTestSources().isEmpty() && !sources.kotlinTestSources().isEmpty()) {
            throw BuildException.actionable(
                    "The test source set combines Groovy and Kotlin, which Zolt does not support.",
                    "Use either Groovy or Kotlin for the test source set, then run `zolt test` again.");
        }
    }
}
