package sh.zolt.plan;

import sh.zolt.generated.GeneratedSourceEvidence;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class BuildPlanGeneratedSourceNodePlanner {
    List<PlanNode> nodes(Path root, List<GeneratedSourceEvidence> generatedSources, String scope) {
        List<PlanNode> nodes = new ArrayList<>();
        for (GeneratedSourceEvidence evidence : generatedSourcesForScope(generatedSources, scope)) {
            nodes.add(generatedSourceNode(root, "generate-" + scope + "-" + evidence.step().id(), evidence));
        }
        return List.copyOf(nodes);
    }

    private static PlanNode generatedSourceNode(Path root, String id, GeneratedSourceEvidence evidence) {
        GeneratedSourceStep step = evidence.step();
        List<PlanBlocker> blockers = new ArrayList<>();
        if (step.kind() != GeneratedSourceKind.DECLARED_ROOT
                && step.kind() != GeneratedSourceKind.OPENAPI
                && step.kind() != GeneratedSourceKind.PROTOBUF) {
            blockers.add(new PlanBlocker(
                    "unsupported-generated-source-kind",
                    "Generated source kind `" + step.kind().configValue() + "` is not supported yet.",
                    "Use declared-root or add support for a Zolt-owned typed generator."));
        }
        if (step.kind() == GeneratedSourceKind.OPENAPI
                && (step.openApi().toolCoordinate().isEmpty()
                        || step.openApi().toolVersion().isEmpty()
                        || step.openApi().generator().isEmpty())) {
            blockers.add(new PlanBlocker(
                    "openapi-generation-incomplete",
                    "OpenAPI generated-source step `" + step.id() + "` is missing tool or generator settings.",
                    "Add [generated.tools.openapi] coordinate/version and generator or preset.generator."));
        }
        if (!"java".equals(step.language())) {
            blockers.add(new PlanBlocker(
                    "unsupported-generated-source-language",
                    "Generated source language `" + step.language() + "` is not supported yet.",
                    "Use language = \"java\" for current generated-source steps."));
        }
        addInvalidPathBlocker(blockers, root, step.output(), "output");
        for (int index = 0; index < step.inputs().size(); index++) {
            String input = step.inputs().get(index);
            addInvalidPathBlocker(blockers, root, input, "input");
            if (!Files.exists(evidence.inputs().get(index))) {
                blockers.add(new PlanBlocker(
                        "missing-generated-source-input",
                        "Generated source input `" + input + "` is missing.",
                        "Create the input file or update [generated."
                                + evidence.scope()
                                + "."
                                + step.id()
                                + "].inputs."));
            }
        }
        if (step.required()
                && (step.kind() == GeneratedSourceKind.DECLARED_ROOT || step.kind() == GeneratedSourceKind.PROTOBUF)
                && !evidence.outputExists()) {
            blockers.add(new PlanBlocker(
                    "missing-generated-source-output",
                    "Required generated source output `" + step.output() + "` is missing.",
                    "Run the generator that produces it, commit the generated sources, "
                            + "or remove the generated-source step."));
        }
        if (step.required() && "stale".equals(evidence.freshness())) {
            blockers.add(new PlanBlocker(
                    "stale-generated-source-output",
                    "Required generated source output `"
                            + step.output()
                            + "` is older than one or more declared inputs.",
                    "Regenerate the source root or update [generated."
                            + evidence.scope()
                            + "."
                            + step.id()
                            + "].inputs."));
        }
        PlanNodeStatus status = blockers.isEmpty()
                ? (step.required() || evidence.outputExists() ? PlanNodeStatus.READY : PlanNodeStatus.SKIPPED)
                : PlanNodeStatus.BLOCKED;
        List<String> details = new ArrayList<>(List.of(
                "sourceRoot: " + evidence.sourceRootId(),
                "scope: " + evidence.scope(),
                "compileLane: " + evidence.compileLane(),
                "kind: " + step.kind().configValue(),
                "language: " + step.language(),
                "ownership: " + evidence.ownership(),
                "outputExists: " + evidence.outputExists(),
                "inputsPresent: " + evidence.inputsPresent(),
                "freshness: " + evidence.freshness(),
                "toolArtifact: " + evidence.toolArtifact()));
        step.openApi().toolVersionRef().ifPresent(versionRef -> details.add("toolVersionRef: " + versionRef));
        details.add("toolFingerprint: " + evidence.toolFingerprint());
        details.add("optionsFingerprint: " + evidence.optionsFingerprint());
        return new PlanNode(
                id,
                "generated-source",
                status,
                step.kind() == GeneratedSourceKind.DECLARED_ROOT
                        ? "Use declared generated Java source root."
                        : "Run typed generated-source step.",
                step.inputs(),
                List.of(step.output()),
                details,
                blockers);
    }

    private static List<GeneratedSourceEvidence> generatedSourcesForScope(
            List<GeneratedSourceEvidence> generatedSources,
            String scope) {
        return generatedSources.stream()
                .filter(evidence -> evidence.scope().equals(scope))
                // exec steps are planned by BuildPlanExecStepNodePlanner, not this typed-generator planner.
                .filter(evidence -> evidence.step().kind() != GeneratedSourceKind.EXEC)
                .toList();
    }

    private static void addInvalidPathBlocker(
            List<PlanBlocker> blockers,
            Path root,
            String configuredPath,
            String field) {
        Path path = Path.of(configuredPath);
        Path resolved = root.resolve(path).normalize();
        if (path.isAbsolute() || !resolved.startsWith(root) || resolved.equals(root)) {
            blockers.add(new PlanBlocker(
                    "invalid-generated-source-" + field,
                    "Generated source "
                            + field
                            + " path `"
                            + configuredPath
                            + "` must be project-relative and under the project directory.",
                    "Use a project-relative path under the project directory."));
        }
    }
}
