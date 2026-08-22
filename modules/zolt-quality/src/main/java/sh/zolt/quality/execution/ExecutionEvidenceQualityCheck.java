package sh.zolt.quality.execution;

import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class ExecutionEvidenceQualityCheck {
    private final ExecutionTestReportEvidenceCheck testReports;
    private final ExecutionCoverageEvidenceCheck coverageReports;

    ExecutionEvidenceQualityCheck() {
        ExecutionSplitEvidence splitEvidence = new ExecutionSplitEvidence();
        this.testReports = new ExecutionTestReportEvidenceCheck(splitEvidence);
        this.coverageReports = new ExecutionCoverageEvidenceCheck(splitEvidence, new ManifestProjectLoader());
    }

    List<QualityCheckResult> checkTestReports(
            Optional<String> member,
            Path projectRoot,
            Path reportsDir,
            Path commandReportsDir,
            Path outputRoot,
            QualityCheckContext context) {
        return testReports.check(
                member,
                projectRoot,
                reportsDir,
                commandReportsDir,
                outputRoot,
                context);
    }

    List<QualityCheckResult> checkCoverageReports(
            Optional<String> member,
            Path projectRoot,
            Path coverageDir,
            Path commandCoverageDir,
            Path outputRoot,
            QualityCheckContext context) {
        return coverageReports.check(
                member,
                projectRoot,
                coverageDir,
                commandCoverageDir,
                outputRoot,
                context);
    }
}
