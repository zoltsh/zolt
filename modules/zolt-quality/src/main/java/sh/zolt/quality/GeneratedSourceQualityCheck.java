package sh.zolt.quality;

import sh.zolt.generated.GeneratedSourceEvidence;
import sh.zolt.generated.GeneratedSourceEvidenceService;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class GeneratedSourceQualityCheck {
    private final GeneratedSourceEvidenceService generatedSourceEvidenceService;

    GeneratedSourceQualityCheck(GeneratedSourceEvidenceService generatedSourceEvidenceService) {
        this.generatedSourceEvidenceService = generatedSourceEvidenceService;
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            Path projectRoot,
            ProjectConfig config) {
        return check(member, projectRoot, config, false);
    }

    List<QualityCheckResult> check(
            Optional<String> member,
            Path projectRoot,
            ProjectConfig config,
            boolean requireOfflineReady) {
        List<GeneratedSourceCheckStep> steps = generatedSourceSteps(config.build());
        if (steps.isEmpty()) {
            return List.of(QualityCheckResult.passed(
                    QualityCheckService.GENERATED_SOURCES,
                    member,
                    config.project().name(),
                    "No declared generated-source steps require validation."));
        }

        List<QualityCheckResult> results = new ArrayList<>();
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Map<String, GeneratedSourceEvidence> evidenceByKey = generatedSourceEvidenceByKey(normalizedRoot, config);
        for (GeneratedSourceCheckStep checkStep : steps) {
            GeneratedSourceStep step = checkStep.step();
            Optional<QualityCheckResult> invalid =
                    invalidGeneratedSourceStep(member, normalizedRoot, config.build(), checkStep);
            if (invalid.isPresent()) {
                results.add(invalid.orElseThrow());
                continue;
            }

            String subject = generatedSection(checkStep);
            if (requireOfflineReady && step.kind() == GeneratedSourceKind.EXEC && "none".equals(step.exec().cache())) {
                results.add(QualityCheckResult.failed(
                        QualityCheckService.GENERATED_SOURCES,
                        member,
                        subject,
                        "Exec step uses cache = \"none\", which always runs and is not offline-reproducible.",
                        "Commit a deterministic input and use cache = \"content\", or drop --require-offline-ready."));
                continue;
            }
            GeneratedSourceEvidence evidence = evidenceByKey.get(generatedSourceKey(checkStep.scope(), step.id()));
            Optional<String> missingInput = firstMissingGeneratedInput(step, evidence);
            if (missingInput.isPresent()) {
                results.add(QualityCheckResult.failed(
                        QualityCheckService.GENERATED_SOURCES,
                        member,
                        subject,
                        "Generated source input `" + missingInput.orElseThrow() + "` is missing.",
                        "Create the input file or update " + subject + ".inputs."));
                continue;
            }
            if (!evidence.outputExists()) {
                if (step.required()) {
                    results.add(QualityCheckResult.failed(
                            QualityCheckService.GENERATED_SOURCES,
                            member,
                            subject,
                            "Generated source root `" + step.output() + "` is missing.",
                            "Run the generator that produces it, commit the generated sources, or remove "
                                    + subject
                                    + " until Zolt supports that generator."));
                    continue;
                }
                results.add(QualityCheckResult.skipped(
                        QualityCheckService.GENERATED_SOURCES,
                        member,
                        subject,
                        "Optional generated source root `" + step.output() + "` is missing.",
                        "Generate it when needed, or set required = true if the root must exist for builds."));
                continue;
            }
            if ("stale".equals(evidence.freshness())) {
                results.add(QualityCheckResult.failed(
                        QualityCheckService.GENERATED_SOURCES,
                        member,
                        subject,
                        "Generated source root `" + step.output() + "` is stale; one or more declared inputs are newer.",
                        "Regenerate the source root or update " + subject + ".inputs."));
                continue;
            }

            results.add(QualityCheckResult.passed(
                    QualityCheckService.GENERATED_SOURCES,
                    member,
                    subject,
                    "Generated source root `"
                            + step.output()
                            + "` is declared and exported as IDE source root `generated-"
                            + checkStep.scope()
                            + "-"
                            + step.id()
                            + "` with ownership `"
                            + evidence.ownership()
                            + "` and freshness `"
                            + evidence.freshness()
                            + "`."));
        }
        return List.copyOf(results);
    }

    private Map<String, GeneratedSourceEvidence> generatedSourceEvidenceByKey(Path projectRoot, ProjectConfig config) {
        Map<String, GeneratedSourceEvidence> evidence = new LinkedHashMap<>();
        for (GeneratedSourceEvidence generatedSource : generatedSourceEvidenceService.evidence(projectRoot, config.build())) {
            evidence.put(generatedSourceKey(generatedSource.scope(), generatedSource.step().id()), generatedSource);
        }
        return Map.copyOf(evidence);
    }

    private static String generatedSourceKey(String scope, String id) {
        return scope + ":" + id;
    }

    private static Optional<String> firstMissingGeneratedInput(
            GeneratedSourceStep step,
            GeneratedSourceEvidence evidence) {
        for (int index = 0; index < step.inputs().size(); index++) {
            if (!Files.exists(evidence.inputs().get(index))) {
                return Optional.of(step.inputs().get(index));
            }
        }
        return Optional.empty();
    }

    private static List<GeneratedSourceCheckStep> generatedSourceSteps(BuildSettings build) {
        List<GeneratedSourceCheckStep> steps = new ArrayList<>();
        for (GeneratedSourceStep step : build.generatedMainSources()) {
            steps.add(new GeneratedSourceCheckStep("main", step));
        }
        for (GeneratedSourceStep step : build.generatedTestSources()) {
            steps.add(new GeneratedSourceCheckStep("test", step));
        }
        return List.copyOf(steps);
    }

    private static Optional<QualityCheckResult> invalidGeneratedSourceStep(
            Optional<String> member,
            Path projectRoot,
            BuildSettings build,
            GeneratedSourceCheckStep checkStep) {
        GeneratedSourceStep step = checkStep.step();
        String subject = generatedSection(checkStep);
        if (step.kind() != GeneratedSourceKind.DECLARED_ROOT
                && step.kind() != GeneratedSourceKind.OPENAPI
                && step.kind() != GeneratedSourceKind.PROTOBUF
                && step.kind() != GeneratedSourceKind.EXEC) {
            return Optional.of(QualityCheckResult.failed(
                    QualityCheckService.GENERATED_SOURCES,
                    member,
                    subject,
                    "Unsupported generated source kind `" + step.kind().configValue() + "`.",
                    "Use declared-root for already generated Java sources."));
        }
        if (step.kind() == GeneratedSourceKind.EXEC) {
            Optional<QualityCheckResult> invalidExec =
                    ExecStepQualityValidation.invalid(member, projectRoot, build, checkStep.scope(), step);
            if (invalidExec.isPresent()) {
                return invalidExec;
            }
        }
        if (!supportedLanguage(step)) {
            return Optional.of(QualityCheckResult.failed(
                    QualityCheckService.GENERATED_SOURCES,
                    member,
                    subject,
                    "Unsupported generated source language `" + step.language() + "`.",
                    "Use language = \"java\", or use language = \"kotlin\" with kind = \"declared-root\""
                            + " or a source-producing exec step."));
        }
        Optional<QualityCheckResult> invalidOutput = invalidGeneratedPath(
                member,
                projectRoot,
                checkStep,
                "output",
                step.output());
        if (invalidOutput.isPresent()) {
            return invalidOutput;
        }
        for (String input : step.inputs()) {
            Optional<QualityCheckResult> invalidInput = invalidGeneratedPath(
                    member,
                    projectRoot,
                    checkStep,
                    "inputs",
                    input);
            if (invalidInput.isPresent()) {
                return invalidInput;
            }
        }
        return Optional.empty();
    }

    private static boolean supportedLanguage(GeneratedSourceStep step) {
        return "java".equals(step.language())
                || ("kotlin".equals(step.language())
                        && (step.kind() == GeneratedSourceKind.DECLARED_ROOT
                                || (step.kind() == GeneratedSourceKind.EXEC
                                        && (step.exec().produces() == ProducesLane.JAVA_SOURCES
                                                || step.exec().produces() == ProducesLane.TEST_SOURCES))));
    }

    private static Optional<QualityCheckResult> invalidGeneratedPath(
            Optional<String> member,
            Path projectRoot,
            GeneratedSourceCheckStep checkStep,
            String field,
            String configuredPath) {
        Path configured = Path.of(configuredPath);
        Path resolved = projectRoot.resolve(configured).normalize();
        if (configured.isAbsolute() || !resolved.startsWith(projectRoot) || resolved.equals(projectRoot)) {
            return Optional.of(QualityCheckResult.failed(
                    QualityCheckService.GENERATED_SOURCES,
                    member,
                    generatedSection(checkStep) + "." + field,
                    "Invalid generated source " + field + " path `" + configuredPath + "`.",
                    "Use a project-relative path under the project directory."));
        }
        return Optional.empty();
    }

    private static String generatedSection(GeneratedSourceCheckStep checkStep) {
        return "[generated." + checkStep.scope() + "." + checkStep.step().id() + "]";
    }

    private record GeneratedSourceCheckStep(String scope, GeneratedSourceStep step) {
    }
}
