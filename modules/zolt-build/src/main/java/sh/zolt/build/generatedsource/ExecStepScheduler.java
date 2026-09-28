package sh.zolt.build.generatedsource;

import sh.zolt.build.BuildException;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Derives a deterministic, serial execution order for exec steps purely from declared IO: an input
 * equal to or under another step's declared output creates an ordering edge. The result is the
 * topological sort of those edges with ties broken alphabetically by id. Steps declaring inputs under
 * the compile output (or using the {@code project} pseudo-tool) run after compilation; a post-compile
 * step may not be the producer for a pre-compile consumer, and cycles are actionable errors.
 */
final class ExecStepScheduler {
    private ExecStepScheduler() {
    }

    static List<GeneratedSourceStep> order(
            Path projectRoot,
            BuildSettings build,
            String scope,
            List<GeneratedSourceStep> steps) {
        Map<String, Path> outputs = new LinkedHashMap<>();
        for (GeneratedSourceStep step : steps) {
            outputs.put(step.id(), projectRoot.resolve(step.output()).normalize());
        }

        Map<String, List<String>> successors = new TreeMap<>();
        Map<String, Integer> indegree = new TreeMap<>();
        for (GeneratedSourceStep step : steps) {
            successors.put(step.id(), new ArrayList<>());
            indegree.put(step.id(), 0);
        }
        Map<String, GeneratedSourceStep> byId = new LinkedHashMap<>();
        steps.forEach(step -> byId.put(step.id(), step));
        for (GeneratedSourceStep consumer : steps) {
            for (String input : consumer.inputs()) {
                Path inputBase = projectRoot.resolve(ExecInputExpander.literalBase(input)).normalize();
                for (GeneratedSourceStep producer : steps) {
                    if (producer.id().equals(consumer.id())) {
                        continue;
                    }
                    if (inputBase.startsWith(outputs.get(producer.id()))
                            && successors.get(producer.id()).stream().noneMatch(consumer.id()::equals)) {
                        rejectPostFeedingPre(projectRoot, build, scope, producer, consumer);
                        successors.get(producer.id()).add(consumer.id());
                        indegree.merge(consumer.id(), 1, Integer::sum);
                    }
                }
            }
        }

        List<String> ready = new ArrayList<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        ready.sort(Comparator.naturalOrder());
        List<GeneratedSourceStep> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove(0);
            ordered.add(byId.get(id));
            for (String next : successors.get(id)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
            ready.sort(Comparator.naturalOrder());
        }
        if (ordered.size() != steps.size()) {
            List<String> remaining = new ArrayList<>();
            indegree.forEach((id, degree) -> {
                if (degree > 0) {
                    remaining.add(id);
                }
            });
            remaining.sort(Comparator.naturalOrder());
            throw BuildException.actionable(
                    "Exec steps have a cyclic input/output dependency among [" + String.join(", ", remaining)
                            + "] in scope `" + scope + "`.",
                    "Break the cycle by removing an input that points at another exec step's output.");
        }
        return List.copyOf(ordered);
    }

    private static void rejectPostFeedingPre(
            Path projectRoot,
            BuildSettings build,
            String scope,
            GeneratedSourceStep producer,
            GeneratedSourceStep consumer) {
        if (ExecStepClassification.isPostCompile(producer, projectRoot, build)
                && !ExecStepClassification.isPostCompile(consumer, projectRoot, build)) {
            throw BuildException.actionable(
                    "Exec step [generated." + scope + "." + consumer.id() + "] consumes the output of post-compile step ["
                            + "generated." + scope + "." + producer.id() + "], but runs before compilation.",
                    "A step that consumes a post-compile step's output must itself be post-compile (produce "
                            + "resources, test-resources, or intermediate).");
        }
    }
}
