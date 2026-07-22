import { expect, type SmokeContext } from "smoque";
import { join } from "node:path";

import { expectTestsFound } from "./assertions.mts";
import { findJsonObjectByString, jsonArray, jsonString, parseJsonObject } from "./json.mts";
import { runZolt, type ZoltRuntime } from "./zolt-runtime.mts";

export async function verifyEnterpriseTests(
  t: SmokeContext,
  zolt: ZoltRuntime,
  project: string,
): Promise<void> {
  await expectTestsFound(t, zolt, 1, [
    "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
    "--reports-dir", "target/test-reports",
  ]);
  await expect.file(join(project, "target/test-reports")).toExist();

  await runZolt(t, zolt, [
    "--no-progress", "coverage", "--cwd", project, "--cache-root", zolt.cacheRoot,
    "--test-event", "failed",
  ]);
  await expect.file(join(project, "target/coverage/jacoco.xml")).toExist();
  await expect.file(join(project, "target/coverage/html")).toExist();
}

export async function verifyEnterpriseMigration(
  t: SmokeContext,
  zolt: ZoltRuntime,
  fixture: string,
): Promise<void> {
  const explanation = await runZolt(t, zolt, [
    "--no-progress", "explain", "--cwd", fixture, "--source", "gradle",
  ]);
  expect.value(explanation.stdout).toContain("maps to Zolt typed OpenAPI generated-source steps");
  expect.value(explanation.stdout).toContain("maps to Zolt WAR and Spring Boot WAR package modes");
  expect.value(explanation.stdout).toContain("did not execute Gradle");

  const blockers = await runZolt(t, zolt, [
    "--no-progress", "explain", "--cwd", fixture, "--source", "gradle", "--blockers", "--format", "json",
  ]);
  const report = parseJsonObject(t, blockers.stdout, "migration blocker report");
  expect.value(jsonString(t, report, "command", "migration blocker report")).toBe("explain-blockers");
  expect.value(jsonString(t, report, "source", "migration blocker report")).toBe("gradle");
  expect.value(jsonString(t, report, "status", "migration blocker report")).toBe("blocked");

  const findings = jsonArray(t, report, "findings", "migration blocker report");
  const customTask = findJsonObjectByString(
    t, findings, "signalId", "gradle.custom-task.detected", "migration blocker report.findings",
  );
  expect.value(jsonString(t, customTask, "sourcePattern", "custom task finding"))
    .toBe("tasks.register(...) or task callbacks");
  expect.value(jsonString(t, customTask, "zoltPrimitive", "custom task finding"))
    .toBe("typed Zolt command primitives");
}
