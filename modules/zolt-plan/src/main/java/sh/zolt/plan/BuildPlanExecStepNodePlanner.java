package sh.zolt.plan;

import sh.zolt.generated.GeneratedSourceEvidence;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProducesLane;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces a typed plan node per exec step. Position is derived from the produces lane and whether the
 * step runs post-compile; blockers cover every failure Zolt can detect statically: an unresolvable
 * tool for the declared runner, an unacknowledged unpinned process tool, jvm tooling absent from the
 * lock, invalid or missing inputs/outputs, a post-compile step that would produce compile sources, and
 * ordering cycles. A {@code cache = "none"} step is informational (nondeterministic marker), not blocked.
 */
final class BuildPlanExecStepNodePlanner {
    List<PlanNode> nodes(
            Path root,
            List<GeneratedSourceEvidence> generatedSources,
            String scope,
            String outputRoot,
            Set<String> lockedToolGroups) {
        List<GeneratedSourceEvidence> execEvidence = generatedSources.stream()
                .filter(evidence -> evidence.scope().equals(scope))
                .filter(evidence -> evidence.step().kind() == GeneratedSourceKind.EXEC)
                .toList();
        if (execEvidence.isEmpty()) {
            return List.of();
        }
        Set<String> cyclicIds = cyclicStepIds(root, execEvidence.stream().map(GeneratedSourceEvidence::step).toList());
        List<PlanNode> nodes = new ArrayList<>();
        for (GeneratedSourceEvidence evidence : execEvidence) {
            nodes.add(execNode(root, outputRoot, scope, evidence, lockedToolGroups, cyclicIds));
        }
        return List.copyOf(nodes);
    }

    private static PlanNode execNode(
            Path root,
            String outputRoot,
            String scope,
            GeneratedSourceEvidence evidence,
            Set<String> lockedToolGroups,
            Set<String> cyclicIds) {
        GeneratedSourceStep step = evidence.step();
        ExecGenerationSettings exec = step.exec();
        String subject = "[generated." + scope + "." + step.id() + "]";
        boolean postCompile = isPostCompile(step, root, outputRoot);
        // A jvm tool is "locked" only when its own isolated closure is present in zolt.lock: an entry
        // tagged with this tool's group. A lock that predates per-tool isolation has no such tag, so the
        // step is reported unlocked and routed to `zolt resolve` rather than a stale global classpath.
        boolean toolLocked = lockedToolGroups.contains(exec.toolName());
        List<PlanBlocker> blockers = new ArrayList<>();
        addToolBlockers(blockers, exec, subject);
        if ("jvm".equals(exec.tool().runner()) && !toolLocked) {
            blockers.add(new PlanBlocker(
                    "exec-tool-not-locked",
                    "Exec tooling for " + subject + " (tool `" + exec.toolName()
                            + "`) is not present in zolt.lock (scope tool-exec).",
                    "Run `zolt resolve` to lock the exec tool coordinates, then rerun `zolt plan`."));
        }
        addInvalidPathBlocker(blockers, root, step.output(), "output");
        for (int index = 0; index < step.inputs().size(); index++) {
            String input = step.inputs().get(index);
            addInvalidPathBlocker(blockers, root, input, "input");
            if (!isGlob(input) && !underCompileOutput(root, outputRoot, input)
                    && !Files.exists(evidence.inputs().get(index))) {
                blockers.add(new PlanBlocker(
                        "missing-exec-input",
                        "Exec input `" + input + "` for " + subject + " is missing.",
                        "Create the input file or update " + subject + ".inputs."));
            }
        }
        if (postCompile
                && (exec.produces() == ProducesLane.JAVA_SOURCES || exec.produces() == ProducesLane.TEST_SOURCES)) {
            blockers.add(new PlanBlocker(
                    "post-compile-produces-sources",
                    "Exec step " + subject + " runs after compilation but produces "
                            + exec.produces().configValue() + ", which would feed that same compile.",
                    "Post-compile steps (project runner / inputs under compiled classes) may only produce "
                            + "resources, test-resources, or intermediate."));
        }
        if (cyclicIds.contains(step.id())) {
            blockers.add(new PlanBlocker(
                    "exec-ordering-cycle",
                    "Exec step " + subject + " is part of a cyclic input/output dependency.",
                    "Break the cycle by removing an input that points at another exec step's output."));
        }
        PlanNodeStatus status = blockers.isEmpty()
                ? (evidence.outputExists() && "fresh".equals(evidence.freshness()) ? PlanNodeStatus.SKIPPED : PlanNodeStatus.READY)
                : PlanNodeStatus.BLOCKED;
        return new PlanNode(
                "exec-" + scope + "-" + step.id(),
                "exec-step",
                status,
                "Run pinned exec tool `" + exec.toolName() + "` on declared inputs.",
                step.inputs(),
                List.of(step.output()),
                details(scope, evidence, exec, postCompile, toolLocked),
                blockers);
    }

    private static void addToolBlockers(List<PlanBlocker> blockers, ExecGenerationSettings exec, String subject) {
        switch (exec.tool().runner()) {
            case "jvm" -> {
                if (exec.tool().mainClass().isBlank() || exec.tool().coordinates().isEmpty()) {
                    blockers.add(unresolvedTool(subject, exec,
                            "Declare [generated.tools." + exec.toolName()
                                    + "] with runner = \"jvm\", coordinates, and mainClass."));
                }
            }
            case "process" -> {
                if (exec.tool().binary().isBlank() || exec.tool().versionCommand().isEmpty()) {
                    blockers.add(unresolvedTool(subject, exec,
                            "Declare [generated.tools." + exec.toolName()
                                    + "] with runner = \"process\", binary, and versionCommand."));
                }
                if (!exec.tool().allowUnpinnedTool()) {
                    blockers.add(new PlanBlocker(
                            "exec-tool-unpinned",
                            "Exec step " + subject + " runs unpinned PATH binary `" + exec.tool().binary()
                                    + "` whose bytes Zolt cannot lock.",
                            "Set allowUnpinnedTool = true on [generated.tools." + exec.toolName()
                                    + "] to acknowledge PATH tool identity rests on the probed version."));
                }
            }
            case "project" -> {
                if (exec.tool().mainClass().isBlank()) {
                    blockers.add(unresolvedTool(subject, exec,
                            "Add mainClass to " + subject + " naming the class to run on the member's classpath."));
                }
            }
            default -> blockers.add(unresolvedTool(subject, exec,
                    "Use runner = \"jvm\" or \"process\", or tool = \"project\"."));
        }
    }

    private static PlanBlocker unresolvedTool(String subject, ExecGenerationSettings exec, String remediation) {
        return new PlanBlocker(
                "unresolved-exec-tool",
                "Exec step " + subject + " references tool `" + exec.toolName() + "` (runner `"
                        + exec.tool().runner() + "`) that is not resolvable.",
                remediation);
    }

    private static List<String> details(
            String scope,
            GeneratedSourceEvidence evidence,
            ExecGenerationSettings exec,
            boolean postCompile,
            boolean toolLocked) {
        List<String> details = new ArrayList<>(List.of(
                "scope: " + scope,
                "tool: " + exec.toolName(),
                "runner: " + exec.tool().runner()));
        switch (exec.tool().runner()) {
            case "jvm" -> {
                details.add("mainClass: " + exec.tool().mainClass());
                details.add("toolLocked: " + toolLocked);
                details.add("toolCoordinates: " + coordinates(exec));
            }
            case "process" -> {
                details.add("binary: " + exec.tool().binary());
                details.add("versionProbe: " + String.join(" ", exec.tool().versionCommand()));
                exec.tool().versionExpect().ifPresent(expect -> details.add("versionExpect: " + expect));
                details.add("allowUnpinnedTool: " + exec.tool().allowUnpinnedTool());
            }
            case "project" -> details.add("mainClass: " + exec.tool().mainClass());
            default -> {
                // unsupported runner already reported as a blocker.
            }
        }
        details.add("produces: " + (exec.produces() == null ? "" : exec.produces().configValue()));
        details.add("derivedPosition: " + derivedPosition(exec.produces(), postCompile));
        details.add("cache: " + exec.cache());
        if ("none".equals(exec.cache())) {
            details.add("nondeterministic: always runs; excluded from --offline; stamps hermetic = false");
        }
        exec.into().ifPresent(into -> details.add("into: " + into));
        if (!exec.secretEnv().isEmpty()) {
            details.add("secretEnv: " + String.join(", ", exec.secretEnv().keySet()));
        }
        if (!exec.inheritEnv().isEmpty()) {
            details.add("inheritEnv: " + String.join(", ", exec.inheritEnv()));
        }
        details.add("outputExists: " + evidence.outputExists());
        details.add("freshness: " + evidence.freshness());
        return details;
    }

    private static String derivedPosition(ProducesLane produces, boolean postCompile) {
        if (postCompile) {
            return "after compile, before resource copy";
        }
        if (produces == null) {
            return "";
        }
        return switch (produces) {
            case JAVA_SOURCES, TEST_SOURCES -> "before compile";
            case RESOURCES -> "before main resource copy";
            case TEST_RESOURCES -> "before test resource copy";
            case INTERMEDIATE -> "on demand (consumed by other exec steps)";
        };
    }

    private static String coordinates(ExecGenerationSettings exec) {
        return exec.tool().coordinates().stream()
                .map(coordinate -> coordinate.coordinate() + ":" + coordinate.version().orElse(""))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private static boolean isPostCompile(GeneratedSourceStep step, Path root, String outputRoot) {
        if ("project".equals(step.exec().tool().runner())) {
            return true;
        }
        return step.inputs().stream().anyMatch(input -> underCompileOutput(root, outputRoot, input));
    }

    private static boolean underCompileOutput(Path root, String outputRoot, String input) {
        Path base = root.resolve(literalBase(input)).normalize();
        return base.startsWith(root.resolve(outputRoot).resolve("classes").normalize())
                || base.startsWith(root.resolve(outputRoot).resolve("test-classes").normalize());
    }

    private static Set<String> cyclicStepIds(Path root, List<GeneratedSourceStep> steps) {
        Map<String, Path> outputs = new HashMap<>();
        for (GeneratedSourceStep step : steps) {
            outputs.put(step.id(), root.resolve(step.output()).normalize());
        }
        Map<String, List<String>> successors = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (GeneratedSourceStep step : steps) {
            successors.put(step.id(), new ArrayList<>());
            indegree.put(step.id(), 0);
        }
        for (GeneratedSourceStep consumer : steps) {
            for (String input : consumer.inputs()) {
                Path base = root.resolve(literalBase(input)).normalize();
                for (GeneratedSourceStep producer : steps) {
                    if (!producer.id().equals(consumer.id())
                            && base.startsWith(outputs.get(producer.id()))
                            && !successors.get(producer.id()).contains(consumer.id())) {
                        successors.get(producer.id()).add(consumer.id());
                        indegree.merge(consumer.id(), 1, Integer::sum);
                    }
                }
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        int removed = 0;
        while (!ready.isEmpty()) {
            String id = ready.poll();
            removed++;
            for (String next : successors.get(id)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (removed == steps.size()) {
            return Set.of();
        }
        Set<String> cyclic = new HashSet<>();
        indegree.forEach((id, degree) -> {
            if (degree > 0) {
                cyclic.add(id);
            }
        });
        return cyclic;
    }

    private static void addInvalidPathBlocker(List<PlanBlocker> blockers, Path root, String configuredPath, String field) {
        Path path = Path.of(configuredPath);
        Path resolved = root.resolve(path).normalize();
        if (path.isAbsolute() || !resolved.startsWith(root) || resolved.equals(root)) {
            blockers.add(new PlanBlocker(
                    "invalid-exec-" + field,
                    "Exec " + field + " path `" + configuredPath + "` must be project-relative and under the project directory.",
                    "Use a project-relative path under the project directory."));
        }
    }

    private static boolean isGlob(String input) {
        return input.indexOf('*') >= 0 || input.indexOf('?') >= 0 || input.indexOf('[') >= 0;
    }

    private static String literalBase(String input) {
        int glob = -1;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '*' || character == '?' || character == '[') {
                glob = index;
                break;
            }
        }
        if (glob < 0) {
            return input;
        }
        String prefix = input.substring(0, glob);
        int slash = prefix.lastIndexOf('/');
        return slash < 0 ? "" : prefix.substring(0, slash);
    }
}
