package sh.zolt.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlanValueTypesTest {
    @TempDir
    private Path projectDir;

    @Test
    void targetParsingAndInclusionFlagsAreDeterministic() {
        assertEquals(Optional.of(PlanTarget.BUILD), PlanTarget.fromConfigValue("build"));
        assertEquals(Optional.of(PlanTarget.TEST), PlanTarget.fromConfigValue("test"));
        assertEquals(Optional.of(PlanTarget.PACKAGE), PlanTarget.fromConfigValue("package"));
        assertEquals(Optional.of(PlanTarget.NATIVE), PlanTarget.fromConfigValue("native"));
        assertEquals(Optional.of(PlanTarget.CI), PlanTarget.fromConfigValue("ci"));
        assertEquals(Optional.empty(), PlanTarget.fromConfigValue(null));
        assertEquals(Optional.empty(), PlanTarget.fromConfigValue("BUILD"));
        assertEquals(Optional.empty(), PlanTarget.fromConfigValue(" build "));
        assertEquals("build, test, package, native, ci", PlanTarget.supportedValues());

        assertFalse(PlanTarget.BUILD.includesTests());
        assertTrue(PlanTarget.TEST.includesTests());
        assertFalse(PlanTarget.PACKAGE.includesCoverage());
        assertTrue(PlanTarget.CI.includesCoverage());
        assertTrue(PlanTarget.PACKAGE.includesPackage());
        assertFalse(PlanTarget.NATIVE.includesPackage());
        assertTrue(PlanTarget.CI.includesPublish());
        assertFalse(PlanTarget.NATIVE.includesPublish());
    }

    @Test
    void planAndNodeRecordsNormalizeDefaultsAndCopyCollections() {
        List<String> inputs = new ArrayList<>();
        inputs.add("src/main/java");
        PlanNode node = new PlanNode("compile-main", "compile", null, "Compile main sources.", inputs, null, null, null);
        inputs.add("src/generated/java");
        List<PlanNode> nodes = new ArrayList<>();
        nodes.add(node);

        BuildPlan plan = new BuildPlan(
                0,
                projectDir.resolve("..").resolve(projectDir.getFileName()),
                projectDir.resolve("..").resolve(projectDir.getFileName()).resolve("zolt.lock"),
                null,
                PlanTarget.BUILD,
                nodes);
        nodes.add(new PlanNode(
                "blocked",
                "diagnostic",
                PlanNodeStatus.BLOCKED,
                "Blocked node added after plan construction.",
                List.of(),
                List.of(),
                List.of(),
                List.of(new PlanBlocker("later", "Later blocker.", "Do not affect the copied plan."))));

        assertEquals(PlanNodeStatus.READY, node.status());
        assertEquals(List.of("src/main/java"), node.inputs());
        assertEquals(List.of(), node.outputs());
        assertEquals(List.of(), node.details());
        assertEquals(List.of(), node.blockers());
        assertThrows(UnsupportedOperationException.class, () -> node.inputs().add("other"));
        assertEquals(1, plan.schemaVersion());
        assertEquals(projectDir.toAbsolutePath().normalize(), plan.projectRoot());
        assertEquals("", plan.projectName());
        assertEquals(List.of(node), plan.nodes());
        assertThrows(UnsupportedOperationException.class, () -> plan.nodes().add(node));
        assertFalse(plan.blocked());
    }

    @Test
    void planReportsBlockedWhenAnyCopiedNodeIsBlocked() {
        PlanNode ready = new PlanNode(
                "compile-main",
                "compile",
                PlanNodeStatus.READY,
                "Compile main sources.",
                List.of(),
                List.of(),
                List.of(),
                List.of());
        PlanNode planned = new PlanNode(
                "coverage",
                "coverage",
                PlanNodeStatus.PLANNED,
                "Run coverage explicitly.",
                List.of(),
                List.of(),
                List.of(),
                List.of());
        PlanNode blocked = new PlanNode(
                "lockfile",
                "resolve",
                PlanNodeStatus.BLOCKED,
                "Lockfile is missing.",
                List.of("zolt.toml"),
                List.of("zolt.lock"),
                List.of(),
                List.of(new PlanBlocker(
                        "missing-lockfile",
                        "zolt.lock is missing; plan will not resolve or download artifacts.",
                        "Run `zolt resolve` first, then rerun `zolt plan`.")));

        BuildPlan plan = new BuildPlan(
                1,
                projectDir,
                projectDir.resolve("zolt.lock"), "demo", PlanTarget.CI, List.of(ready, planned, blocked));

        assertTrue(plan.blocked());
    }

    @Test
    void nodeValidationErrorsNameTheInvalidField() {
        IllegalArgumentException missingId = assertThrows(
                IllegalArgumentException.class,
                () -> new PlanNode(" ", "compile", PlanNodeStatus.READY, "description", List.of(), List.of(), List.of(), List.of()));
        IllegalArgumentException missingKind = assertThrows(
                IllegalArgumentException.class,
                () -> new PlanNode("id", "", PlanNodeStatus.READY, "description", List.of(), List.of(), List.of(), List.of()));
        IllegalArgumentException missingDescription = assertThrows(
                IllegalArgumentException.class,
                () -> new PlanNode("id", "compile", PlanNodeStatus.READY, null, List.of(), List.of(), List.of(), List.of()));

        assertEquals("Plan node id must be a non-empty string.", missingId.getMessage());
        assertEquals("Plan node kind must be a non-empty string.", missingKind.getMessage());
        assertEquals("Plan node description must be a non-empty string.", missingDescription.getMessage());
    }

    @Test
    void blockerValidationErrorsNameTheInvalidField() {
        IllegalArgumentException missingCode =
                assertThrows(IllegalArgumentException.class, () -> new PlanBlocker(null, "message", "next"));
        IllegalArgumentException missingMessage =
                assertThrows(IllegalArgumentException.class, () -> new PlanBlocker("code", " ", "next"));
        IllegalArgumentException missingNextStep =
                assertThrows(IllegalArgumentException.class, () -> new PlanBlocker("code", "message", ""));

        assertEquals("Plan blocker code must be a non-empty string.", missingCode.getMessage());
        assertEquals("Plan blocker message must be a non-empty string.", missingMessage.getMessage());
        assertEquals("Plan blocker next step must be a non-empty string.", missingNextStep.getMessage());
    }
}
