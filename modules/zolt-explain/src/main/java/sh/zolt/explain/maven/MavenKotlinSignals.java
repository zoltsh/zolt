package sh.zolt.explain.maven;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.ExplainSignals;

/** Selects the migration signal for an exact kotlin-maven-plugin declaration. */
final class MavenKotlinSignals {
    private MavenKotlinSignals() {
    }

    static ExplainSignal signal(String project, MavenPluginInspection plugin) {
        if (MavenSignalRules.boundedKotlinJvmCompilation(plugin)) {
            return ExplainSignals.MAVEN_KOTLIN_MANUAL_MIGRATION.signal(
                    project,
                    "Plugin `" + plugin.coordinate()
                            + "` declares bounded Kotlin/JVM compilation; `zolt explain --emit-toml`"
                            + " drafts it only when the complete project matches the strict conventional subset.");
        }
        if (MavenSignalRules.passiveKotlinJvmDeclaration(plugin)) {
            return ExplainSignals.MAVEN_KOTLIN_MANUAL_MIGRATION.signal(
                    project,
                    "Plugin `" + plugin.coordinate() + "` is declared without active bounded Kotlin/JVM"
                            + " lifecycle behavior and requires manual migration review.");
        }
        return ExplainSignals.MAVEN_LANGUAGE_UNSUPPORTED.signal(
                project,
                "Plugin `" + plugin.coordinate()
                        + "` declares Kotlin behavior outside Zolt's bounded Kotlin/JVM surface.");
    }
}
