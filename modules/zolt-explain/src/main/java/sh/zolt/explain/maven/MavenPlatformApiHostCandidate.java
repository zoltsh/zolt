package sh.zolt.explain.maven;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.ExplainSignals;
import java.util.Optional;

/**
 * Decides whether a statically inspected Maven project is a  host-platform-API candidate:
 * it set {@code source}/{@code target} (not {@code <release>}) to a Java level below the build JDK
 * feature version, meaning Maven compiled it against the host JDK's platform API rather than a
 * ct.sym-pinned {@code --release} surface.
 *
 * <p>Such a project keeps Zolt's strict {@code --release} default, but the emit path offers a
 * commented {@code jdkApi = "host"} escape hatch and the audit fires
 * {@code maven.compiler.platform-api-host-candidate}. A {@code <release>} POM is never a candidate:
 * it already declared reproducible-API intent.
 */
public final class MavenPlatformApiHostCandidate {
    private MavenPlatformApiHostCandidate() {
    }

    /** The host-candidate audit signal for a project, or empty when it does not apply. */
    public static Optional<ExplainSignal> signal(String project, MavenProjectInspection inspection) {
        if (!applies(inspection)) {
            return Optional.empty();
        }
        return Optional.of(ExplainSignals.MAVEN_COMPILER_PLATFORM_API_HOST_CANDIDATE.signal(
                project,
                "Maven set source/target `" + inspection.javaVersion()
                        + "` below the build JDK, so it compiled against the host JDK's platform API."
                        + " Zolt defaults to reproducible --release " + inspection.javaVersion()
                        + "; consider [compiler] jdkApi = \"host\" only if a post-target platform"
                        + " API fails the strict build."));
    }

    public static boolean applies(MavenProjectInspection inspection) {
        if (inspection.javaVersionProvenance() != MavenJavaVersionProvenance.SOURCE_TARGET) {
            return false;
        }
        Optional<Integer> level = featureVersion(inspection.javaVersion());
        return level.isPresent() && level.orElseThrow() < buildJdkFeatureVersion();
    }

    private static int buildJdkFeatureVersion() {
        return Runtime.version().feature();
    }

    private static Optional<Integer> featureVersion(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip();
        if (normalized.startsWith("1.") && normalized.length() > 2) {
            normalized = normalized.substring(2);
        }
        int end = 0;
        while (end < normalized.length() && Character.isDigit(normalized.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(normalized.substring(0, end)));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
