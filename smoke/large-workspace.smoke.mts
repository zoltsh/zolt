import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { copyFixture, expectTestsFound, packagedZolt, runZolt, singleJar } from "./support/zolt-smoke.mts";

smoke.suite("large workspace lifecycle smoke", { tags: ["examples", "workspace"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-large-workspace");
  const zolt = await packagedZolt(t);

  await t.step("builds and runs a selected member with its dependencies", async () => {
    const workspace = await copyFixture(root, work, "large-workspace");
    const memberArgs = ["--workspace", "--member", "apps/app", "--cwd", workspace, "--cache-root", zolt.cacheRoot];
    await runZolt(t, zolt, ["--no-progress", "resolve", "--workspace", "--cwd", workspace, "--cache-root", zolt.cacheRoot]);
    await expectTestsFound(t, zolt, 1, ["--no-progress", "test", ...memberArgs]);
    await runZolt(t, zolt, ["--no-progress", "build", ...memberArgs]);
    await runZolt(t, zolt, ["--no-progress", "package", ...memberArgs]);
    await expect.file(await singleJar(join(workspace, "apps/app/target"))).toExist();
    const result = await runZolt(t, zolt, ["--no-progress", "run-package", ...memberArgs]);
    expect.value(result.stdout).toMatch(/large-workspace:/u);
  });
});
