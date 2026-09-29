package sh.zolt.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.generated.GeneratedSourceEvidence;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolCoordinate;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProtobufGenerationSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildPlanExecStepNodePlannerTest {
    private static final ExecToolSettings JVM_TOOL = new ExecToolSettings(
            "jvm",
            List.of(new ExecToolCoordinate("org.jooq:jooq-codegen", Optional.of("3.19.15"), Optional.empty())),
            "org.jooq.codegen.GenerationTool");

    private final BuildPlanExecStepNodePlanner planner = new BuildPlanExecStepNodePlanner();

    @TempDir
    private Path tempDir;

    @Test
    void readyWhenToolLockedAndInputsPresent() throws IOException {
        writeInput("src/main/jooq/config.xml");
        GeneratedSourceStep step = execStep("model", "target/generated/sources/jooq",
                List.of("src/main/jooq/config.xml"), ProducesLane.JAVA_SOURCES, JVM_TOOL);

        PlanNode node = node(step, true, false, "missing");

        assertEquals("exec-step", node.kind());
        assertEquals(PlanNodeStatus.READY, node.status());
        assertTrue(node.blockers().isEmpty(), node.blockers().toString());
        assertTrue(node.details().contains("language: java"));
        assertTrue(node.details().contains("tool: tool"));
        assertTrue(node.details().contains("derivedPosition: before compile"));
        assertTrue(node.details().contains("toolCoordinates: org.jooq:jooq-codegen:3.19.15"));
    }

    @Test
    void processToolReadyWithoutLockAndShowsProbeDetails() throws IOException {
        writeInput("web/package.json");
        ExecToolSettings tool = ExecToolSettings.process(
                "npm", List.of("npm", "--version"), Optional.of(">=10 <11"), true);
        GeneratedSourceStep step = execStep("frontend", "web/node_modules",
                List.of("web/package.json"), ProducesLane.INTERMEDIATE, tool);

        PlanNode node = node(step, false, false, "missing");

        assertEquals(PlanNodeStatus.READY, node.status());
        assertTrue(node.blockers().isEmpty(), node.blockers().toString());
        assertTrue(node.details().contains("runner: process"));
        assertTrue(node.details().contains("binary: npm"));
        assertTrue(node.details().contains("versionProbe: npm --version"));
        assertTrue(node.details().contains("versionExpect: >=10 <11"));
        assertTrue(node.details().contains("derivedPosition: on demand (consumed by other exec steps)"));
    }

    @Test
    void blocksUnpinnedProcessToolWithoutAcknowledgement() throws IOException {
        writeInput("web/package.json");
        ExecToolSettings tool = ExecToolSettings.process("npm", List.of("npm", "--version"), Optional.empty(), false);
        GeneratedSourceStep step = execStep("frontend", "web/node_modules",
                List.of("web/package.json"), ProducesLane.INTERMEDIATE, tool);

        assertBlocker(node(step, false, false, "missing"), "exec-tool-unpinned");
    }

    @Test
    void cacheNoneIsMarkedNondeterministicNotBlocked() throws IOException {
        writeInput("src/main/jooq/config.xml");
        ExecGenerationSettings exec = new ExecGenerationSettings(
                "tool", JVM_TOOL, List.of(), ProducesLane.JAVA_SOURCES, Optional.empty(), Map.of(), "none");
        GeneratedSourceStep step = new GeneratedSourceStep(
                "model", GeneratedSourceKind.EXEC, "java", "target/generated/sources/jooq",
                List.of("src/main/jooq/config.xml"), true, true,
                OpenApiGenerationSettings.empty(), ProtobufGenerationSettings.empty(), exec);

        PlanNode node = node(step, true, false, "missing");

        assertTrue(node.blockers().isEmpty(), node.blockers().toString());
        assertTrue(node.details().contains("cache: none"));
        assertTrue(node.details().stream().anyMatch(detail -> detail.startsWith("nondeterministic:")),
                node.details().toString());
    }

    @Test
    void projectRunnerSchedulesAfterCompile() {
        GeneratedSourceStep step = execStep("gen", "target/generated/res",
                List.of("target/classes"), ProducesLane.RESOURCES, ExecToolSettings.project("com.example.Gen"));

        PlanNode node = node(step, false, false, "missing");

        assertTrue(node.blockers().isEmpty(), node.blockers().toString());
        assertTrue(node.details().contains("derivedPosition: after compile, before resource copy"));
    }

    @Test
    void blocksWhenExecToolingNotLocked() throws IOException {
        writeInput("src/main/jooq/config.xml");
        GeneratedSourceStep step = execStep("model", "target/generated/sources/jooq",
                List.of("src/main/jooq/config.xml"), ProducesLane.JAVA_SOURCES, JVM_TOOL);

        PlanNode node = node(step, false, false, "missing");

        assertEquals(PlanNodeStatus.BLOCKED, node.status());
        assertBlocker(node, "exec-tool-not-locked");
    }

    @Test
    void blocksMissingInput() {
        GeneratedSourceStep step = execStep("model", "target/generated/sources/jooq",
                List.of("src/main/jooq/config.xml"), ProducesLane.JAVA_SOURCES, JVM_TOOL);

        assertBlocker(node(step, true, false, "input-missing"), "missing-exec-input");
    }

    @Test
    void blocksOutputOutsideProject() throws IOException {
        writeInput("src/main/jooq/config.xml");
        GeneratedSourceStep step = execStep("model", "../outside",
                List.of("src/main/jooq/config.xml"), ProducesLane.JAVA_SOURCES, JVM_TOOL);

        assertBlocker(node(step, true, false, "missing"), "invalid-exec-output");
    }

    @Test
    void blocksPostCompileStepProducingSources() {
        GeneratedSourceStep step = execStep("model", "target/generated/sources/jooq",
                List.of("target/classes/com/example/App.class"), ProducesLane.JAVA_SOURCES, JVM_TOOL);

        assertBlocker(node(step, true, false, "missing"), "post-compile-produces-sources");
    }

    @Test
    void customCompileOutputUsesTheSamePostCompileClassificationAsExecution() {
        GeneratedSourceStep step = execStep("model", "target/generated/sources/jooq",
                List.of("out/main/com/example/App.class"), ProducesLane.JAVA_SOURCES, JVM_TOOL);
        BuildSettings build = new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target",
                "out/main",
                "out/test");

        PlanNode node = node(step, build, true, false, "missing");

        assertBlocker(node, "post-compile-produces-sources");
        assertTrue(node.details().contains("derivedPosition: after compile, before resource copy"));
    }

    @Test
    void blocksUnresolvedTool() throws IOException {
        writeInput("src/main/jooq/config.xml");
        GeneratedSourceStep step = execStep("model", "target/generated/sources/jooq",
                List.of("src/main/jooq/config.xml"), ProducesLane.JAVA_SOURCES, ExecToolSettings.empty());

        assertBlocker(node(step, true, false, "missing"), "unresolved-exec-tool");
    }

    @Test
    void blocksOrderingCycle() {
        GeneratedSourceStep a = execStep("a", "target/generated/a",
                List.of("target/generated/b/out.txt"), ProducesLane.JAVA_SOURCES, JVM_TOOL);
        GeneratedSourceStep b = execStep("b", "target/generated/b",
                List.of("target/generated/a/out.txt"), ProducesLane.JAVA_SOURCES, JVM_TOOL);

        List<PlanNode> nodes = planner.nodes(
                tempDir.normalize(),
                List.of(evidence("main", a, false, "missing"), evidence("main", b, false, "missing")),
                "main",
                BuildSettings.defaults(),
                Set.of("tool"));

        assertEquals(2, nodes.size());
        nodes.forEach(node -> assertBlocker(node, "exec-ordering-cycle"));
    }

    @Test
    void acceptsKotlinForSourceProducingExecSteps() throws IOException {
        writeInput("src/main/jooq/config.xml");
        GeneratedSourceStep step = execStep(
                "model",
                "kotlin",
                "target/generated/sources/jooq",
                List.of("src/main/jooq/config.xml"),
                ProducesLane.JAVA_SOURCES,
                JVM_TOOL);

        PlanNode node = node(step, true, false, "missing");

        assertEquals(PlanNodeStatus.READY, node.status());
        assertTrue(node.details().contains("language: kotlin"));
    }

    @Test
    void blocksKotlinForNonSourceExecSteps() throws IOException {
        writeInput("src/main/jooq/config.xml");
        GeneratedSourceStep step = execStep(
                "model",
                "kotlin",
                "target/generated/resources/jooq",
                List.of("src/main/jooq/config.xml"),
                ProducesLane.RESOURCES,
                JVM_TOOL);

        assertBlocker(node(step, true, false, "missing"), "unsupported-exec-language");
    }

    private PlanNode node(GeneratedSourceStep step, boolean toolLocked, boolean outputExists, String freshness) {
        return node(step, BuildSettings.defaults(), toolLocked, outputExists, freshness);
    }

    private PlanNode node(
            GeneratedSourceStep step,
            BuildSettings build,
            boolean toolLocked,
            boolean outputExists,
            String freshness) {
        List<PlanNode> nodes = planner.nodes(
                tempDir.normalize(),
                List.of(evidence("main", step, outputExists, freshness)),
                "main",
                build,
                toolLocked ? Set.of("tool") : Set.of());
        assertEquals(1, nodes.size());
        return nodes.getFirst();
    }

    private GeneratedSourceEvidence evidence(String scope, GeneratedSourceStep step, boolean outputExists, String freshness) {
        List<Path> inputs = step.inputs().stream().map(input -> tempDir.resolve(input).normalize()).toList();
        boolean inputsPresent = inputs.stream().allMatch(Files::exists);
        return new GeneratedSourceEvidence(
                step.id(),
                "generated." + scope + "." + step.id(),
                scope,
                step,
                tempDir.resolve(step.output()).normalize(),
                inputs,
                outputExists,
                inputsPresent,
                freshness,
                "zolt-owned-clean",
                scope + "-compile",
                "",
                "",
                "");
    }

    private void writeInput(String path) throws IOException {
        Path input = tempDir.resolve(path);
        Files.createDirectories(input.getParent());
        Files.writeString(input, "<configuration/>\n");
    }

    private static GeneratedSourceStep execStep(
            String id,
            String output,
            List<String> inputs,
            ProducesLane produces,
            ExecToolSettings tool) {
        return execStep(id, "java", output, inputs, produces, tool);
    }

    private static GeneratedSourceStep execStep(
            String id,
            String language,
            String output,
            List<String> inputs,
            ProducesLane produces,
            ExecToolSettings tool) {
        ExecGenerationSettings exec = new ExecGenerationSettings(
                "tool", tool, List.of(), produces, Optional.empty(), Map.of(), "content");
        return new GeneratedSourceStep(
                id,
                GeneratedSourceKind.EXEC,
                language,
                output,
                inputs,
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                exec);
    }

    private static void assertBlocker(PlanNode node, String code) {
        assertTrue(
                node.blockers().stream().anyMatch(blocker -> blocker.code().equals(code)),
                "Expected blocker " + code + " in " + node.blockers());
    }
}
