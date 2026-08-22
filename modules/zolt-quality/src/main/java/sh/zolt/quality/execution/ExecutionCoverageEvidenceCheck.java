package sh.zolt.quality.execution;

import static sh.zolt.quality.QualityCheckService.EXECUTION_CONTEXT;

import sh.zolt.project.CoverageSettings;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.quality.QualityCheckContext;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.quality.QualityCheckText;
import sh.zolt.quality.coverage.CoverageFloorEvaluation;
import sh.zolt.quality.coverage.CoverageMeasurement;
import sh.zolt.quality.coverage.CoverageReportException;
import sh.zolt.quality.coverage.JacocoCoverageReport;
import sh.zolt.quality.execution.ExecutionSplitEvidence.ShardEvidenceManifest;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Design §4.5: a member's coverage floor is the maximum of the workspace root's floor and its own,
 * so the floors this check enforces are read through the composing project loader. Reading the
 * member's authored manifest alone would let a member silently escape a root floor.
 */
final class ExecutionCoverageEvidenceCheck {
    private final ExecutionSplitEvidence splitEvidence;
    private final ManifestProjectLoader projectLoader;

    ExecutionCoverageEvidenceCheck(ExecutionSplitEvidence splitEvidence, ManifestProjectLoader projectLoader) {
        this.splitEvidence = splitEvidence;
        this.projectLoader = projectLoader;
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            Path projectRoot,
            Path coverageDir,
            Path commandCoverageDir,
            Path outputRoot,
            QualityCheckContext context) {
        if (context != QualityCheckContext.CI || coverageDir == null) {
            return List.of();
        }
        Path root = ProjectPaths.root(projectRoot);
        Path absoluteCoverageDir;
        try {
            absoluteCoverageDir = ProjectPaths.output(root, "--coverage-dir", coverageDir.toString());
        } catch (ProjectPathException exception) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    "--coverage-dir",
                    exception.getMessage(),
                    "Use a path such as `target/coverage`."));
        }
        if (!Files.isDirectory(absoluteCoverageDir)) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    QualityCheckText.displayPath(projectRoot, absoluteCoverageDir),
                    "CI context expected coverage reports, but the coverage directory is missing.",
                    coverageReportsNextStep(member, commandCoverageDir)));
        }
        try {
            List<ShardEvidenceManifest> shardManifests = splitEvidence.shardManifests(root, outputRoot);
            List<String> workerIds = splitEvidence.workerIds(absoluteCoverageDir.resolve("workers").resolve("zolt-workers.json"));
            Path execFile = absoluteCoverageDir.resolve("jacoco.exec");
            if (!ProjectPaths.isRegularFileInsideProject(root, "--coverage-dir", execFile)) {
                List<QualityCheckResult> splitCoverageFailures = splitCoverageFailures(
                        member,
                        root,
                        projectRoot,
                        absoluteCoverageDir,
                        commandCoverageDir,
                        shardManifests,
                        workerIds);
                if (splitCoverageFailures.isEmpty() && (!splitEvidence.nonEmpty(shardManifests).isEmpty() || !workerIds.isEmpty())) {
                    return List.of(QualityCheckResult.passed(
                            EXECUTION_CONTEXT,
                            member,
                            "coverage-reports",
                            "CI coverage preflight found split Jacoco evidence."));
                }
                if (!splitCoverageFailures.isEmpty()) {
                    return splitCoverageFailures;
                }
                return List.of(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        QualityCheckText.displayPath(projectRoot, execFile),
                        "CI context expected Jacoco execution data, but jacoco.exec is missing.",
                        coverageReportsNextStep(member, commandCoverageDir)));
            }
            CoverageReportCounts counts = countCoverageReports(root, absoluteCoverageDir);
            if (counts.xmlReports() == 0 && counts.htmlReports() == 0) {
                return List.of(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        QualityCheckText.displayPath(projectRoot, absoluteCoverageDir),
                        "CI context expected coverage XML or HTML reports, but none were found.",
                        coverageReportsNextStep(member, commandCoverageDir)));
            }
            List<QualityCheckResult> splitCoverageFailures = splitCoverageFailures(
                    member,
                    root,
                    projectRoot,
                    absoluteCoverageDir,
                    commandCoverageDir,
                    shardManifests,
                    workerIds);
            if (!splitCoverageFailures.isEmpty()) {
                return splitCoverageFailures;
            }
            List<QualityCheckResult> floorFailures = coverageFloorFailures(
                    member,
                    root,
                    projectRoot,
                    absoluteCoverageDir,
                    commandCoverageDir);
            if (!floorFailures.isEmpty()) {
                return floorFailures;
            }
            return List.of(QualityCheckResult.passed(
                    EXECUTION_CONTEXT,
                    member,
                    "coverage-reports",
                    "CI coverage preflight found Jacoco execution data, "
                            + counts.xmlReports()
                            + " XML "
                            + QualityCheckText.plural(counts.xmlReports(), "report", "reports")
                            + ", and "
                            + counts.htmlReports()
                            + " HTML "
                            + QualityCheckText.plural(counts.htmlReports(), "report", "reports")
                            + "."));
        } catch (IOException exception) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    QualityCheckText.displayPath(projectRoot, absoluteCoverageDir),
                    "Could not inspect coverage reports: " + exception.getMessage(),
                    "Check coverage directory permissions, then rerun `zolt check --context ci --coverage-dir " + commandCoverageDir + "`."));
        } catch (ProjectPathException exception) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    QualityCheckText.displayPath(projectRoot, absoluteCoverageDir),
                    exception.getMessage(),
                    "Remove symlinked coverage entries that escape the project, then rerun `zolt check --context ci --coverage-dir " + commandCoverageDir + "`."));
        }
    }

    private List<QualityCheckResult> coverageFloorFailures(
            Optional<String> member,
            Path root,
            Path projectRoot,
            Path absoluteCoverageDir,
            Path commandCoverageDir) {
        if (!Files.isRegularFile(root.resolve("zolt.toml"))) {
            // "No config" reads as "no floors", so coverage evidence for a directory without a
            // manifest stays evidence rather than becoming a floor failure.
            return List.of();
        }
        CoverageSettings floors = projectLoader.coverageFloors(root);
        if (!floors.hasAnyFloor()) {
            return List.of();
        }
        Path jacocoXml = absoluteCoverageDir.resolve("jacoco.xml");
        if (!ProjectPaths.isRegularFileInsideProject(root, "--coverage-dir", jacocoXml)) {
            // Floors are configured but no aggregate XML report is present (for example split-only
            // coverage); keep the existing evidence behavior rather than inventing a new requirement.
            return List.of();
        }
        CoverageMeasurement measurement;
        try {
            measurement = JacocoCoverageReport.read(jacocoXml);
        } catch (CoverageReportException exception) {
            return List.of(QualityCheckResult.failed(
                    EXECUTION_CONTEXT,
                    member,
                    QualityCheckText.displayPath(projectRoot, jacocoXml),
                    "Could not read the Jacoco coverage report: " + exception.getMessage(),
                    "Rerun `zolt coverage` to regenerate " + commandCoverageDir
                            + "/jacoco.xml, then rerun `zolt check --context ci --coverage-dir "
                            + commandCoverageDir + "`."));
        }
        CoverageFloorEvaluation evaluation = CoverageFloorEvaluation.evaluate(floors, measurement);
        if (evaluation.passed()) {
            return List.of();
        }
        return List.of(QualityCheckResult.failed(
                EXECUTION_CONTEXT,
                member,
                "coverage-floors",
                evaluation.summary(),
                coverageFloorNextStep(member, commandCoverageDir)));
    }

    private static String coverageFloorNextStep(Optional<String> member, Path commandCoverageDir) {
        String workspace = member.isPresent() ? " --workspace" : "";
        return "Raise coverage to the configured floors or adjust the [coverage] floors in zolt.toml, then rerun "
                + "`zolt check" + workspace + " --context ci --coverage-dir " + commandCoverageDir + "`.";
    }

    private static String coverageReportsNextStep(Optional<String> member, Path commandCoverageDir) {
        if (member.isPresent()) {
            return "Run `zolt coverage` from each selected member so coverage evidence exists under "
                    + commandCoverageDir
                    + ", then rerun `zolt check --workspace --context ci --coverage-dir "
                    + commandCoverageDir
                    + "`.";
        }
        return "Run `zolt coverage` so coverage evidence exists under "
                + commandCoverageDir
                + ", then rerun `zolt check --context ci --coverage-dir "
                + commandCoverageDir
                + "`.";
    }

    private static CoverageReportCounts countCoverageReports(Path projectRoot, Path coverageDir) throws IOException {
        long xmlReports;
        long htmlReports;
        try (Stream<Path> paths = Files.walk(coverageDir)) {
            List<Path> files = paths
                    .filter(path -> ProjectPaths.isRegularFileInsideProject(projectRoot, "--coverage-dir", path))
                    .toList();
            xmlReports = files.stream().filter(ExecutionCoverageEvidenceCheck::isCoverageXmlReport).count();
            htmlReports = files.stream().filter(ExecutionCoverageEvidenceCheck::isCoverageHtmlReport).count();
        }
        return new CoverageReportCounts(xmlReports, htmlReports);
    }

    private static boolean isCoverageXmlReport(Path path) {
        return path.getFileName().toString().endsWith(".xml");
    }

    private static boolean isCoverageHtmlReport(Path path) {
        return path.getFileName().toString().endsWith(".html");
    }

    private List<QualityCheckResult> splitCoverageFailures(
            Optional<String> member,
            Path root,
            Path projectRoot,
            Path coverageDir,
            Path commandCoverageDir,
            List<ShardEvidenceManifest> shardManifests,
            List<String> workerIds) throws IOException {
        List<QualityCheckResult> failures = new ArrayList<>();
        for (ShardEvidenceManifest manifest : splitEvidence.nonEmpty(shardManifests)) {
            Path expectedDir = coverageDir.resolve("shards").resolve(manifest.suiteSegment()).resolve(manifest.shardSegment());
            Path execFile = expectedDir.resolve("jacoco.exec");
            if (!ProjectPaths.isRegularFileInsideProject(root, "--coverage-dir", execFile)) {
                failures.add(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        QualityCheckText.displayPath(projectRoot, execFile),
                        "CI context expected Jacoco execution data for shard `"
                                + manifest.displayName()
                                + "`, but jacoco.exec is missing.",
                        coverageShardNextStep(member, manifest, commandCoverageDir)));
                continue;
            }
            CoverageReportCounts counts = countCoverageReports(root, expectedDir);
            if (counts.xmlReports() == 0 && counts.htmlReports() == 0) {
                failures.add(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        QualityCheckText.displayPath(projectRoot, expectedDir),
                        "CI context expected coverage XML or HTML reports for shard `"
                                + manifest.displayName()
                                + "`, but none were found.",
                        coverageShardNextStep(member, manifest, commandCoverageDir)));
            }
        }
        for (String workerId : workerIds) {
            Path execFile = coverageDir.resolve("workers").resolve(workerId).resolve("jacoco.exec");
            if (!ProjectPaths.isRegularFileInsideProject(root, "--coverage-dir", execFile)) {
                failures.add(QualityCheckResult.failed(
                        EXECUTION_CONTEXT,
                        member,
                        QualityCheckText.displayPath(projectRoot, execFile),
                        "CI context expected Jacoco execution data for worker `"
                                + workerId
                                + "`, but jacoco.exec is missing.",
                        "Rerun `zolt coverage` so worker coverage evidence is regenerated under "
                                + commandCoverageDir
                                + "."));
            }
        }
        return failures;
    }

    private String coverageShardNextStep(
            Optional<String> member,
            ShardEvidenceManifest manifest,
            Path commandCoverageDir) {
        String workspace = member.isPresent() ? " --workspace" : "";
        return "Run `zolt coverage"
                + workspace
                + " --suite "
                + splitEvidence.shellArgument(manifest.suiteName())
                + " --shard "
                + manifest.index()
                + "/"
                + manifest.total()
                + "`, then rerun `zolt check --context ci --coverage-dir "
                + commandCoverageDir
                + "`.";
    }

    private record CoverageReportCounts(long xmlReports, long htmlReports) {
    }
}
