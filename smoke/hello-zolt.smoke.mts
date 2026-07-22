import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { copyFixture, packagedZolt, runZolt, singleJar } from "./support/zolt-smoke.mts";

smoke.suite("hello Zolt lifecycle smoke", { tags: ["examples", "lifecycle"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-hello");
  const zolt = await packagedZolt(t);

  await t.step("resolves, builds, packages, and runs", async () => {
    const project = await copyFixture(root, work, "hello-zolt");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expect.file(await singleJar(join(project, "target"))).toExist();
    const result = await runZolt(t, zolt, [
      "--no-progress", "run-package", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(result.stdout).toContain("Hello from Zolt");
  });
});
