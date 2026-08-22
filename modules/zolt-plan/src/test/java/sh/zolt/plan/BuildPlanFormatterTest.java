package sh.zolt.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildPlanFormatterTest {
    private final BuildPlanFormatter formatter = new BuildPlanFormatter();

    @TempDir
    private Path projectDir;

    @Test
    void formatsTextWithStableNodeOrderAndActionableBlockers() {
        BuildPlan plan = new BuildPlan(
                1,
                projectDir,
                projectDir.resolve("zolt.lock"),
                "demo",
                PlanTarget.PACKAGE,
                List.of(
                        new PlanNode(
                                "lockfile",
                                "resolve",
                                PlanNodeStatus.READY,
                                "Read lockfile.",
                                List.of("zolt.toml", "zolt.lock"),
                                List.of(),
                                List.of("freshness: locked"),
                                List.of()),
                        new PlanNode(
                                "assemble-package",
                                "package",
                                PlanNodeStatus.BLOCKED,
                                "Assemble package.",
                                List.of(),
                                List.of("target/demo-1.0.0.jar"),
                                List.of(),
                                List.of(new PlanBlocker(
                                        "missing-main-class",
                                        "Spring Boot package modes require [project].main.",
                                        "Add [project].main to zolt.toml.")))));

        String expected = """
                Zolt plan
                Project: demo
                Root: %s
                Lockfile: %s
                Target: package
                Status: blocked
                Nodes: 2
                - lockfile [resolve] ready - Read lockfile.
                  inputs: zolt.toml, zolt.lock
                  details: freshness: locked
                - assemble-package [package] blocked - Assemble package.
                  outputs: target/demo-1.0.0.jar
                  blocker missing-main-class: Spring Boot package modes require [project].main.
                    next: Add [project].main to zolt.toml.
                """
                .formatted(
                        projectDir.toAbsolutePath().normalize(),
                        projectDir.toAbsolutePath().normalize().resolve("zolt.lock"));

        assertEquals(expected, formatter.text(plan));
    }

    @Test
    void formatsJsonWithStableStructureAndEscapedStrings() {
        String escapedDetail = "quote \" slash \\ backspace \b form \f newline \n carriage \r tab \t control "
                + (char) 0x1f;
        BuildPlan plan = new BuildPlan(
                1,
                projectDir,
                projectDir.resolve("zolt.lock"),
                "demo",
                PlanTarget.CI,
                List.of(
                        new PlanNode(
                                "compile-main",
                                "compile",
                                PlanNodeStatus.READY,
                                "Compile main Java sources.",
                                List.of("src/main/java"),
                                List.of("target/classes"),
                                List.of(escapedDetail),
                                List.of()),
                        new PlanNode(
                                "lockfile",
                                "resolve",
                                PlanNodeStatus.BLOCKED,
                                "Dependency graph is not locked yet.",
                                List.of("zolt.toml"),
                                List.of("zolt.lock"),
                                List.of(),
                                List.of(new PlanBlocker(
                                        "missing-lockfile",
                                        "zolt.lock is missing; plan will not resolve or download artifacts.",
                                        "Run `zolt resolve` first, then rerun `zolt plan`.")))));

        String expected = """
                {
                  "schemaVersion": 1,
                  "projectRoot": "%s",
                  "lockfilePath": "%s",
                  "workspaceLockfile": false,
                  "project": "demo",
                  "target": "ci",
                  "status": "blocked",
                  "nodes": [
                    {
                      "id": "compile-main",
                      "kind": "compile",
                      "status": "ready",
                      "description": "Compile main Java sources.",
                      "inputs": ["src/main/java"],
                      "outputs": ["target/classes"],
                      "details": ["quote \\" slash \\\\ backspace \\b form \\f newline \\n carriage \\r tab \\t control \\u001f"],
                      "blockers": []
                    },
                    {
                      "id": "lockfile",
                      "kind": "resolve",
                      "status": "blocked",
                      "description": "Dependency graph is not locked yet.",
                      "inputs": ["zolt.toml"],
                      "outputs": ["zolt.lock"],
                      "details": [],
                      "blockers": [
                        {
                          "code": "missing-lockfile",
                          "message": "zolt.lock is missing; plan will not resolve or download artifacts.",
                          "nextStep": "Run `zolt resolve` first, then rerun `zolt plan`."
                        }
                      ]
                    }
                  ]
                }
                """
                .formatted(
                        projectDir.toAbsolutePath().normalize().toString().replace("\\", "\\\\"),
                        projectDir.toAbsolutePath().normalize().resolve("zolt.lock").toString()
                                .replace("\\", "\\\\"));

        assertEquals(expected, formatter.json(plan));
    }

    @Test
    void formatsJsonWithMultiValueArraysAndMultipleBlockers() {
        BuildPlan plan = new BuildPlan(
                1,
                projectDir,
                projectDir.resolve("zolt.lock"),
                "demo",
                PlanTarget.PACKAGE,
                List.of(new PlanNode(
                        "assemble-package",
                        "package",
                        PlanNodeStatus.BLOCKED,
                        "Assemble package.",
                        List.of("target/classes", "zolt.lock"),
                        List.of("target/demo-1.0.0.jar", "target/demo-1.0.0-sources.jar"),
                        List.of("mode: spring-boot", "manifest: generated"),
                        List.of(
                                new PlanBlocker(
                                        "missing-main-class",
                                        "Spring Boot package modes require [project].main.",
                                        "Add [project].main to zolt.toml."),
                                new PlanBlocker(
                                        "missing-lockfile",
                                        "zolt.lock is missing; plan will not resolve or download artifacts.",
                                        "Run `zolt resolve` first, then rerun `zolt plan`.")))));

        String json = formatter.json(plan);

        assertTrue(json.contains("\"inputs\": [\"target/classes\", \"zolt.lock\"]"));
        assertTrue(json.contains(
                "\"outputs\": [\"target/demo-1.0.0.jar\", \"target/demo-1.0.0-sources.jar\"]"));
        assertTrue(json.contains("\"details\": [\"mode: spring-boot\", \"manifest: generated\"]"));
        assertTrue(json.contains("""
                      "blockers": [
                        {
                          "code": "missing-main-class",
                          "message": "Spring Boot package modes require [project].main.",
                          "nextStep": "Add [project].main to zolt.toml."
                        },
                        {
                          "code": "missing-lockfile",
                          "message": "zolt.lock is missing; plan will not resolve or download artifacts.",
                          "nextStep": "Run `zolt resolve` first, then rerun `zolt plan`."
                        }
                      ]
                """));
    }

    @Test
    void formatsEmptyReadyPlanAsEmptyNodeArray() {
        BuildPlan plan = new BuildPlan(
                0,
                projectDir,
                projectDir.resolve("zolt.lock"), null, PlanTarget.BUILD, null);

        String json = formatter.json(plan);

        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"project\": \"\""));
        assertTrue(json.contains("\"status\": \"ready\""));
        assertTrue(json.endsWith("\"nodes\": []\n}\n"));
    }

    @Test
    void formatsEmptyReadyPlanAsTextSummaryWithoutNodeRows() {
        BuildPlan plan = new BuildPlan(
                0,
                projectDir.resolve("."),
                projectDir.resolve(".").resolve("zolt.lock"), null, PlanTarget.BUILD, null);

        String expected = "Zolt plan\n"
                + "Project: \n"
                + "Root: "
                + projectDir.toAbsolutePath().normalize()
                + "\n"
                + "Lockfile: "
                + projectDir.toAbsolutePath().normalize().resolve("zolt.lock")
                + "\n"
                + "Target: build\n"
                + "Status: ready\n"
                + "Nodes: 0\n";

        assertEquals(expected, formatter.text(plan));
    }
}
