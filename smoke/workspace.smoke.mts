import { expect, smoke, type SmokeContext } from "smoque";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { copyFixture, packagedZolt, runZolt, writeOutput } from "./support/zolt-smoke.mts";

smoke.suite("zolt workspace smoke", { tags: ["jvm", "workspace"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-workspace-smoke");
  const zolt = await packagedZolt(t);

  await t.step("workspace root and nested member builds work", async () => {
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
      "--member",
      "apps/api",
      "--cwd",
      workspace,
      "--cache-root",
      zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, [
      "--no-progress",
      "build",
      "--workspace",
      "--member",
      "apps/api",
      "--cwd",
      join(workspace, "apps/api"),
      "--cache-root",
      zolt.cacheRoot,
    ]);

    await expect.file(join(workspace, "apps/api/target/classes/com/example/workspace/api/ApiApplication.class")).toExist();
  });

  await t.step("workspace clean respects selected member boundaries", async () => {
    const workspace = await copyFixture(root, work, "workspace-app", "workspace-clean-app");

    await writeFile(
      join(workspace, "zolt.toml"),
      `[workspace]
name = "workspace-app"

[workspace.members]
default = ["apps/api"]
include = ["apps/api", "modules/core", "apps/worker"]

[workspace.project]
group = "com.example.workspace"
version = "0.1.0"
java = 21
`,
      "utf8",
    );
    await mkdir(join(workspace, "apps/worker/src/main/java/com/example/workspace/worker"), { recursive: true });
    await writeFile(
      join(workspace, "apps/worker/zolt.toml"),
      `[project]
name = "worker"
version = "0.1.0"
group = "com.example.workspace"
java = 21
`,
      "utf8",
    );
    await writeFile(
      join(workspace, "apps/worker/src/main/java/com/example/workspace/worker/Worker.java"),
      "package com.example.workspace.worker;\n\npublic final class Worker {\n}\n",
      "utf8",
    );
    await writeOutput(join(workspace, "modules/core/target/classes/com/example/workspace/core/Greeting.class"));
    await writeOutput(join(workspace, "apps/api/target/classes/com/example/workspace/api/ApiApplication.class"));
    await writeOutput(join(workspace, "apps/worker/target/classes/com/example/workspace/worker/Worker.class"));
    await writeOutput(join(workspace, "target/root-report/report.txt"));
    await writeOutput(join(workspace, ".zolt/cache/artifact.jar"));
    await writeOutput(join(workspace, "apps/api/.zolt/cache/artifact.jar"));
    await writeFile(join(workspace, "zolt.lock"), "# smoke lockfile\n", "utf8");

    const result = await runZolt(t, zolt, [
      "--no-progress",
      "clean",
      "--workspace",
      "--member",
      "apps/api",
      "--cwd",
      workspace,
    ]);
    expect.value(result.stdout).toContain("Deleted 2 workspace build output paths across 2 members");
    expect.value(result.stdout).toContain("Deleted modules/core ");
    expect.value(result.stdout).toContain("Deleted apps/api ");

    await expect.file(join(workspace, "modules/core/target")).notToExist();
    await expect.file(join(workspace, "apps/api/target")).notToExist();
    await expect.file(join(workspace, "apps/worker/target/classes/com/example/workspace/worker/Worker.class")).toExist();
    await expect.file(join(workspace, "target/root-report/report.txt")).toExist();
    await expect.file(join(workspace, "zolt.lock")).toExist();
    await expect.file(join(workspace, ".zolt/cache/artifact.jar")).toExist();
    await expect.file(join(workspace, "apps/api/.zolt/cache/artifact.jar")).toExist();
    await expect.file(join(workspace, "apps/api/src/main/java/com/example/workspace/api/ApiApplication.java")).toExist();
    await expect.file(join(workspace, "modules/core/src/main/java/com/example/workspace/core/Greeting.java")).toExist();
  });
});
