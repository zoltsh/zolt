import { expect, type SmokeContext } from "smoque";

import { runZolt, type ZoltRuntime } from "./zolt-runtime.mts";

export async function verifyEnterpriseReleaseChecks(
  t: SmokeContext,
  zolt: ZoltRuntime,
  project: string,
): Promise<void> {
  const dryRun = await runZolt(t, zolt, ["--no-progress", "publish", "--cwd", project, "--dry-run"]);
  expect.value(dryRun.stdout).toContain("Version kind: release");
  expect.value(dryRun.stdout).toContain("Target repository: company-releases");
  expect.value(dryRun.stdout).toContain("Artifact path: target/spring-boot-enterprise-canary-0.1.0.war");
  expect.value(dryRun.stdout).toContain("No upload was performed.");

  const ci = await runZolt(t, zolt, [
    "--no-progress", "check", "--cwd", project, "--context", "ci",
    "--reports-dir", "target/test-reports", "--coverage-dir", "target/coverage",
    "--require-package", "--require-publish-dry-run",
  ]);
  expect.value(ci.stdout).toContain("ok execution-context ci CI context policy is active");
  expect.value(ci.stdout).toContain("CI test report preflight found");
  expect.value(ci.stdout).toContain("CI coverage preflight found Jacoco execution data");
  expect.value(ci.stdout).toContain("CI publish dry-run preflight is ready");

  const release = await runZolt(t, zolt, [
    "--no-progress", "publish", "--cwd", project, "--context", "release", "--dry-run",
  ], { check: false });
  expect.value(release.exitCode === 0).toBeFalsy();
  const output = `${release.stdout}\n${release.stderr}`;
  expect.value(output).toContain("Policy source: built-in release context");
  expect.value(output).toContain("release context requires a sources jar");
  expect.value(output).toContain("release context requires a javadoc jar");

  await runZolt(t, zolt, [
    "--no-progress", "resolve", "--locked", "--offline", "--cwd", project, "--cache-root", zolt.cacheRoot,
  ]);
}
