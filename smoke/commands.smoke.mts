import { expect, smoke, type SmokeContext } from "smoque";

import {
  copyFixture,
  expectCommandFailureContains,
  expectTestsFound,
  parseJsonObject,
  packagedZolt,
  runZolt,
} from "./support/zolt-smoke.mts";
import { expectNoAnsi } from "./support/terminal-output.mts";

smoke.suite("zolt command options smoke", { tags: ["commands"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-command-options");
  const zolt = await packagedZolt(t);

  await t.step("root and global output options run from the packaged CLI", async () => {
    const list = await runZolt(t, zolt, ["--color=never", "--list"], { timeout: "30s" });
    expect.value(list.stdout).toContain("resolve");
    expect.value(list.stdout).toContain("package");
    expect.value(list.stdout).toContain("self");
    expect.value(list.stdout).toContain("update");

    const quietHelp = await runZolt(t, zolt, ["--quiet", "help"], { timeout: "30s" });
    expect.value(quietHelp.stdout).toContain("The modern Java build toolkit.");

    const noProgressHelp = await runZolt(t, zolt, ["--progress=never", "help"], { timeout: "30s" });
    expect.value(noProgressHelp.stdout).toContain("Usage: zolt");

    await expectCommandFailureContains(
      t,
      zolt,
      ["--color", "rainbow", "help"],
      "expected one of: auto, always, never",
    );
    await expectCommandFailureContains(
      t,
      zolt,
      ["--progress", "sparkles", "help"],
      "expected one of: auto, always, never",
    );
  });

  await t.step("machine-readable command options stay parseable with human color enabled", async () => {
    const project = await copyFixture(root, work, "hello-zolt");

    const plan = await runZolt(t, zolt, [
      "--color=always",
      "plan",
      "--cwd",
      project,
      "--format",
      "json",
    ], { check: false });
    expectNoAnsi(t, plan.stdout, "plan json stdout");
    parseJsonObject(t, plan.stdout, "plan json stdout");
    expect.value(plan.stdout).toContain("\"target\": \"package\"");

    await expectCommandFailureContains(
      t,
      zolt,
      [
        "--no-progress",
        "package",
        "--cwd",
        project,
        "--format",
        "json",
        "--cache-root",
        zolt.cacheRoot,
      ],
      "Use `zolt package --plan --format json`.",
    );

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    const packagePlan = await runZolt(t, zolt, [
      "--color=always",
      "package",
      "--plan",
      "--format",
      "json",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
    ]);
    expectNoAnsi(t, packagePlan.stdout, "package plan json stdout");
    parseJsonObject(t, packagePlan.stdout, "package plan json stdout");
    expect.value(packagePlan.stdout).toContain("\"mode\": \"thin\"");
    expect.value(packagePlan.stdout).toContain("\"archive\":");
    expect.value(packagePlan.stdout).toContain("\"dependencies\": [");
  });

  await t.step("lockfile, offline, and classpath options work after resolve", async () => {
    const project = await copyFixture(root, work, "hello-zolt", "hello-zolt-lock-options");

    await expectCommandFailureContains(
      t,
      zolt,
      ["--no-progress", "resolve", "--locked", "--cwd", project, "--cache-root", zolt.cacheRoot],
      "zolt.lock",
    );
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, [
      "--no-progress",
      "resolve",
      "--locked",
      "--offline",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
    ]);

    const audit = await runZolt(t, zolt, [
      "--no-progress",
      "classpath",
      "--cwd",
      project,
      "audit",
      "--format",
      "json",
      "--cache-root",
      zolt.cacheRoot,
    ]);
    parseJsonObject(t, audit.stdout, "classpath audit json stdout");
    expect.value(audit.stdout).toContain("\"command\": \"classpath audit\"");

    await expectCommandFailureContains(
      t,
      zolt,
      [
        "--no-progress",
        "classpath",
        "--cwd",
        project,
        "compile",
        "--format",
        "json",
        "--cache-root",
        zolt.cacheRoot,
      ],
      "Use `zolt classpath audit --format json`.",
    );
  });

  await t.step("comma-split workspace member options drive build and test commands", async () => {
    const workspace = await copyFixture(root, work, "workspace-app");

    await runZolt(t, zolt, [
      "--no-progress",
      "resolve",
      "--workspace",
      "--cwd",
      workspace,
      "--cache-root",
      zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, [
      "--no-progress",
      "build",
      "--workspace",
      "--members",
      "modules/core,apps/api",
      "--cwd",
      workspace,
      "--cache-root",
      zolt.cacheRoot,
    ]);
    await expectTestsFound(t, zolt, 1, [
      "--no-progress",
      "test",
      "--workspace",
      "--members",
      "apps/api",
      "--test",
      "com.example.workspace.api.ApiApplicationTest",
      "--cwd",
      workspace,
      "--cache-root",
      zolt.cacheRoot,
    ]);
  });
});
