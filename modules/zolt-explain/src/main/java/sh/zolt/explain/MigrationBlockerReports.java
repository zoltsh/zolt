package sh.zolt.explain;

import java.util.Comparator;
import java.util.List;

public final class MigrationBlockerReports {
    private MigrationBlockerReports() {
    }

    public static MigrationBlockerReport from(MigrationReadinessScorecard scorecard) {
        List<MigrationReadinessFinding> findings = scorecard.concerns().stream()
                .flatMap(concern -> concern.findings().stream())
                .filter(MigrationBlockerReports::isBlockingOrRisky)
                .sorted(Comparator
                        .comparingInt((MigrationReadinessFinding finding) -> categoryRank(finding.category()))
                        .thenComparingInt(finding -> severityRank(finding.severity()))
                        .thenComparing(MigrationBlockerReports::sourcePattern)
                        .thenComparing(MigrationReadinessFinding::zoltPrimitive)
                        .thenComparing(MigrationReadinessFinding::followUp)
                        .thenComparing(MigrationReadinessFinding::message))
                .toList();
        List<String> nextSteps = findings.stream()
                .map(MigrationReadinessFinding::nextStep)
                .distinct()
                .sorted()
                .toList();
        return new MigrationBlockerReport(
                scorecard.source(),
                scorecard.root(),
                status(findings),
                findings,
                nextSteps);
    }

    private static boolean isBlockingOrRisky(MigrationReadinessFinding finding) {
        return switch (finding.category()) {
            case BLOCKED, NON_DETERMINISTIC, UNKNOWN, UNSUPPORTED -> true;
            case PLANNED -> finding.severity() == ExplainSignal.Severity.BLOCK
                    || finding.signalId().equals("maven.annotation-processor.path")
                    || finding.signalId().equals("maven.reactor.detected")
                    || finding.signalId().equals("maven.kotlin.manual-migration")
                    || finding.signalId().equals("gradle.custom-task.detected")
                    || finding.signalId().equals("gradle.kotlin.manual-migration")
                    || finding.signalId().equals("gradle.openapi.generated-sources")
                    || finding.signalId().equals("gradle.publication.detected");
            case SUPPORTED -> false;
        };
    }

    private static String status(List<MigrationReadinessFinding> findings) {
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.UNSUPPORTED)) {
            return "unsupported";
        }
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.BLOCKED)) {
            return "blocked";
        }
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.NON_DETERMINISTIC)) {
            return "non-deterministic";
        }
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.UNKNOWN)) {
            return "unknown";
        }
        if (findings.stream().anyMatch(finding -> finding.category() == MigrationReadinessCategory.PLANNED)) {
            return "planned";
        }
        return "ready";
    }

    private static int categoryRank(MigrationReadinessCategory category) {
        return switch (category) {
            case BLOCKED -> 0;
            case UNSUPPORTED -> 1;
            case NON_DETERMINISTIC -> 2;
            case UNKNOWN -> 3;
            case PLANNED -> 4;
            case SUPPORTED -> 5;
        };
    }

    private static int severityRank(ExplainSignal.Severity severity) {
        return switch (severity) {
            case BLOCK -> 0;
            case UNKNOWN -> 1;
            case WARN -> 2;
            case OK -> 3;
        };
    }

    static String sourcePattern(MigrationReadinessFinding finding) {
        String sourcePattern = finding.sourcePattern();
        if (!sourcePattern.startsWith("concern:")) {
            return sourcePattern;
        }
        int end = sourcePattern.indexOf(' ');
        return sourcePattern.substring(end + 1);
    }
}
