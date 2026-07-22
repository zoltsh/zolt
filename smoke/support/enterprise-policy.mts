import { expect, type SmokeContext } from "smoque";

import { runZolt, type ZoltRuntime } from "./zolt-runtime.mts";

export async function verifyEnterprisePolicy(
  t: SmokeContext,
  zolt: ZoltRuntime,
  project: string,
): Promise<void> {
  await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);

  const policy = await runZolt(t, zolt, ["--no-progress", "policy", "--cwd", project]);
  expect.value(policy.stdout).toContain("spring-boot-starter-logging");
  expect.value(policy.stdout).toContain("tomcat-embed-core");

  const classpath = await runZolt(t, zolt, [
    "--no-progress", "classpath", "audit", "--format", "json",
    "--cwd", project, "--cache-root", zolt.cacheRoot,
  ]);
  for (const scope of ["provided", "processor", "dev", "tool-openapi"]) {
    expect.value(classpath.stdout).toContain(`"scope": "${scope}"`);
  }
}

export async function verifyEnterpriseBuildPlan(
  t: SmokeContext,
  zolt: ZoltRuntime,
  project: string,
): Promise<void> {
  await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
  const generated = await runZolt(t, zolt, [
    "--no-progress", "check", "--cwd", project, "--check", "generated-sources",
  ]);
  expect.value(generated.stdout).toContain("freshness `fresh`");
  expect.value(generated.stdout).toContain("ownership `zolt-owned-openapi`");

  const plan = await runZolt(t, zolt, [
    "--no-progress", "plan", "--cwd", project, "--target", "ci", "--format", "json",
  ]);
  expect.value(plan.stdout).toContain('"id": "generate-main-public-api"');
  expect.value(plan.stdout).toContain('"id": "coverage"');
  expect.value(plan.stdout).toContain('"id": "publish-dry-run"');
  expect.value(plan.stdout).toContain('"status": "ready"');
}
