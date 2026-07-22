import { expect, type SmokeContext } from "smoque";
import { join } from "node:path";

import { runZolt, type ZoltRuntime } from "./zolt-runtime.mts";

export async function verifyEnterprisePackage(
  t: SmokeContext,
  zolt: ZoltRuntime,
  project: string,
): Promise<string> {
  const plan = await runZolt(t, zolt, [
    "--no-progress", "package", "--mode", "spring-boot-war", "--plan", "--format", "json",
    "--cwd", project, "--cache-root", zolt.cacheRoot,
  ]);
  for (const rule of [
    "spring-boot-war-provided-lib",
    "dev-only-omitted",
    "processor-omitted",
    "openapi-tool-omitted",
    "coverage-tool-omitted",
  ]) {
    expect.value(plan.stdout).toContain(`"rule": "${rule}"`);
  }

  await runZolt(t, zolt, [
    "--no-progress", "package", "--mode", "spring-boot-war",
    "--cwd", project, "--cache-root", zolt.cacheRoot,
  ]);
  const war = join(project, "target/spring-boot-enterprise-canary-0.1.0.war");
  await expect.archive(war).toContainEntries([
    "WEB-INF/classes/application.properties",
    "WEB-INF/classes/com/example/enterprise/generated/model/Status200Response.class",
    "WEB-INF/lib-provided/tomcat-embed-core-11.0.21.jar",
  ]);
  const jarTool = await t.tools.jar({ minVersion: 8 });
  const entries = await t.cmd(jarTool.command, ["tf", war], { cwd: project });
  expect.value(entries.stdout.includes("spring-boot-devtools")).toBeFalsy();
  expect.value(/^WEB-INF\/lib\/lombok-/mu.test(entries.stdout)).toBeFalsy();
  expect.value(entries.stdout).toMatch(/^WEB-INF\/lib-provided\/lombok-[^/]+\.jar$/mu);

  const evidence = await runZolt(t, zolt, [
    "--no-progress", "check", "--cwd", project, "--check", "package-contents",
  ]);
  expect.value(evidence.stdout).toContain("Package mode `spring-boot-war`");
  expect.value(evidence.stdout).toContain("rule:spring-boot-war-provided-lib");
  expect.value(evidence.stdout).toContain("rule:dev-only-omitted");
  expect.value(evidence.stdout).toContain("rule:processor-omitted");
  return war;
}
