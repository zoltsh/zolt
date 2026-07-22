import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { copyFixture, expectTestsFound, packagedZolt, runZolt, singleJar } from "./support/zolt-smoke.mts";

smoke.suite("Micronaut annotation processing smoke", { tags: ["framework", "micronaut"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-micronaut");
  const zolt = await packagedZolt(t);

  await t.step("builds generated metadata, tests, and packages the application", async () => {
    const project = await copyFixture(root, work, "micronaut-http");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTestsFound(t, zolt, 1, [
      "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);

    await expect.file(join(project, "target/classes/com/example/micronaut/$HelloController$Definition.class")).toExist();
    const jar = await singleJar(join(project, "target"));
    await expect.archive(jar).toContainEntries([
      "com/example/micronaut/HelloController.class",
      "com/example/micronaut/$HelloController$Definition.class",
    ]);
  });
});
