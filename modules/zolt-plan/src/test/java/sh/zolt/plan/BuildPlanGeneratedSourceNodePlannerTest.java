package sh.zolt.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.generated.GeneratedSourceEvidence;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildPlanGeneratedSourceNodePlannerTest {
    private final BuildPlanGeneratedSourceNodePlanner planner = new BuildPlanGeneratedSourceNodePlanner();

    @TempDir
    private Path tempDir;

    @Test
    void buildsGeneratedSourceNodeDetailsForMatchingScope() throws IOException {
        Path input = tempDir.resolve("src/main/openapi/api.yaml");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "openapi: 3.1.0\n");
        GeneratedSourceEvidence main = evidence(
                "main",
                new GeneratedSourceStep(
                        "api",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "kotlin",
                        "target/generated/sources/api",
                        List.of("src/main/openapi/api.yaml"),
                        true,
                        true),
                true,
                true,
                "fresh");
        GeneratedSourceEvidence test = evidence(
                "test",
                new GeneratedSourceStep(
                        "fixtures",
                        GeneratedSourceKind.DECLARED_ROOT,
                        "java",
                        "target/generated/test-sources/fixtures",
                        List.of(),
                        false,
                        true),
                false,
                true,
                "missing");

        List<PlanNode> nodes = planner.nodes(tempDir, List.of(test, main), "main");

        assertEquals(1, nodes.size());
        PlanNode node = nodes.getFirst();
        assertEquals("generate-main-api", node.id());
        assertEquals("generated-source", node.kind());
        assertEquals(PlanNodeStatus.READY, node.status());
        assertEquals(List.of("src/main/openapi/api.yaml"), node.inputs());
        assertEquals(List.of("target/generated/sources/api"), node.outputs());
        assertTrue(node.details().contains("sourceRoot: generated.main.api"));
        assertTrue(node.details().contains("scope: main"));
        assertTrue(node.details().contains("compileLane: main"));
        assertTrue(node.details().contains("kind: declared-root"));
        assertTrue(node.details().contains("language: kotlin"));
        assertTrue(node.details().contains("freshness: fresh"));
        assertTrue(node.details().contains("toolFingerprint: tool"));
        assertTrue(node.blockers().isEmpty());
    }

    @Test
    void blocksMissingStaleAndInvalidGeneratedSourceInputs() {
        GeneratedSourceStep step = new GeneratedSourceStep(
                "api",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                "../outside",
                List.of("src/main/openapi/missing.yaml"),
                true,
                true);

        PlanNode node = planner.nodes(
                        tempDir,
                        List.of(evidence("main", step, false, false, "stale")),
                        "main")
                .getFirst();

        assertEquals(PlanNodeStatus.BLOCKED, node.status());
        assertBlocker(node, "invalid-generated-source-output");
        assertBlocker(node, "missing-generated-source-input");
        assertBlocker(node, "missing-generated-source-output");
        assertBlocker(node, "stale-generated-source-output");
    }

    @Test
    void blocksIncompleteOpenApiSettingsAndUnknownLanguage() {
        GeneratedSourceStep step = new GeneratedSourceStep(
                "openapi",
                GeneratedSourceKind.OPENAPI,
                "scala",
                "target/generated/sources/openapi",
                List.of(),
                true,
                true,
                OpenApiGenerationSettings.empty());

        PlanNode node = planner.nodes(
                        tempDir,
                        List.of(evidence("main", step, false, true, "missing")),
                        "main")
                .getFirst();

        assertEquals(PlanNodeStatus.BLOCKED, node.status());
        assertBlocker(node, "openapi-generation-incomplete");
        assertBlocker(node, "unsupported-generated-source-language");
    }

    @Test
    void blocksProtobufStepWithEscapingInputAndMissingRequiredOutput() {
        GeneratedSourceStep step = new GeneratedSourceStep(
                "proto",
                GeneratedSourceKind.PROTOBUF,
                "java",
                "target/generated/sources/proto",
                List.of("../outside.proto", "/absolute/schema.proto"),
                true,
                true);

        PlanNode node = planner.nodes(
                        tempDir,
                        List.of(evidence("main", step, false, false, "missing")),
                        "main")
                .getFirst();

        assertEquals(PlanNodeStatus.BLOCKED, node.status());
        assertEquals("Run typed generated-source step.", node.description());
        assertEquals(List.of("../outside.proto", "/absolute/schema.proto"), node.inputs());
        assertTrue(node.details().contains("kind: protobuf"));
        assertBlocker(node, "invalid-generated-source-input");
        assertBlocker(node, "missing-generated-source-input");
        assertBlocker(node, "missing-generated-source-output");
        PlanBlocker missingOutput = blocker(node, "missing-generated-source-output");
        assertEquals("Required generated source output `target/generated/sources/proto` is missing.", missingOutput.message());
        assertEquals(
                "Run the generator that produces it, commit the generated sources, "
                        + "or remove the generated-source step.",
                missingOutput.nextStep());
    }

    @Test
    void skipsOptionalDeclaredRootWhenOutputIsMissing() {
        GeneratedSourceStep step = new GeneratedSourceStep(
                "optional",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                "target/generated/sources/optional",
                List.of(),
                false,
                true);

        PlanNode node = planner.nodes(
                        tempDir,
                        List.of(evidence("main", step, false, true, "missing")),
                        "main")
                .getFirst();

        assertEquals(PlanNodeStatus.SKIPPED, node.status());
        assertEquals("Use declared generated source root.", node.description());
        assertTrue(node.blockers().isEmpty());
    }

    @Test
    void blocksGeneratedSourceOutputThatResolvesToProjectRoot() {
        GeneratedSourceStep step = new GeneratedSourceStep(
                "root",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                ".",
                List.of(),
                true,
                true);

        PlanNode node = planner.nodes(
                        tempDir,
                        List.of(evidence("main", step, false, true, "missing")),
                        "main")
                .getFirst();

        PlanBlocker blocker = blocker(node, "invalid-generated-source-output");
        assertEquals(PlanNodeStatus.BLOCKED, node.status());
        assertEquals(
                "Generated source output path `.` must be project-relative and under the project directory.",
                blocker.message());
        assertEquals("Use a project-relative path under the project directory.", blocker.nextStep());
    }

    @Test
    void returnsNoNodesWhenGeneratedSourcesDoNotMatchRequestedScope() {
        GeneratedSourceStep step = new GeneratedSourceStep(
                "fixtures",
                GeneratedSourceKind.DECLARED_ROOT,
                "java",
                "target/generated/test-sources/fixtures",
                List.of(),
                false,
                true);

        List<PlanNode> nodes = planner.nodes(
                tempDir,
                List.of(evidence("test", step, false, true, "missing")),
                "main");

        assertTrue(nodes.isEmpty());
    }

    @Test
    void plansCompleteOpenApiStepWithVersionReferenceDetails() throws IOException {
        Path input = tempDir.resolve("src/main/openapi/api.yaml");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "openapi: 3.1.0\n");
        OpenApiGenerationSettings openApi = new OpenApiGenerationSettings(
                Optional.of("org.openapitools:openapi-generator-cli"),
                Optional.of("7.12.0"),
                Optional.of("openapi-generator"),
                Optional.empty(),
                Optional.of("kotlin"),
                Optional.empty(),
                Optional.of("com.example.api"),
                Optional.of("com.example.model"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(true),
                Map.of("dateLibrary", "java8"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of());
        GeneratedSourceStep step = new GeneratedSourceStep(
                "client",
                GeneratedSourceKind.OPENAPI,
                "kotlin",
                "target/generated/sources/client",
                List.of("src/main/openapi/api.yaml"),
                true,
                true,
                openApi);

        PlanNode node = planner.nodes(
                        tempDir,
                        List.of(evidence("main", step, false, true, "missing")),
                        "main")
                .getFirst();

        assertEquals(PlanNodeStatus.READY, node.status());
        assertEquals("Run typed generated-source step.", node.description());
        assertTrue(node.details().contains("ownership: zolt-owned-openapi"));
        assertTrue(node.details().contains("toolArtifact: org.openapitools:openapi-generator-cli:7.12.0"));
        assertTrue(node.details().contains("toolVersionRef: openapi-generator"));
        assertTrue(node.blockers().isEmpty());
    }

    private GeneratedSourceEvidence evidence(
            String scope,
            GeneratedSourceStep step,
            boolean outputExists,
            boolean inputsPresent,
            String freshness) {
        return new GeneratedSourceEvidence(
                step.id(),
                "generated." + scope + "." + step.id(),
                scope,
                step,
                tempDir.resolve(step.output()).normalize(),
                step.inputs().stream().map(input -> tempDir.resolve(input).normalize()).toList(),
                outputExists,
                inputsPresent,
                freshness,
                step.kind() == GeneratedSourceKind.OPENAPI ? "zolt-owned-openapi" : "user-managed",
                scope,
                step.openApi().toolCoordinate()
                        .flatMap(coordinate -> step.openApi().toolVersion().map(version -> coordinate + ":" + version))
                        .orElse("none"),
                "tool",
                "options");
    }

    private static void assertBlocker(PlanNode node, String code) {
        assertTrue(
                node.blockers().stream().anyMatch(blocker -> blocker.code().equals(code)),
                "Expected blocker " + code + " in " + node.blockers());
    }

    private static PlanBlocker blocker(PlanNode node, String code) {
        return node.blockers().stream()
                .filter(blocker -> blocker.code().equals(code))
                .findFirst()
                .orElseThrow();
    }
}
