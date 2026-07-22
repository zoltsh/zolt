import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import {
  copyFixture,
  expectTestsFound,
  expectTextFile,
  packagedZolt,
  runZolt,
  singleJar,
} from "./support/zolt-smoke.mts";

smoke.suite("JUnit Vintage engine smoke", { tags: ["junit", "vintage"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-junit-vintage");
  const zolt = await packagedZolt(t);

  await t.step("runs JUnit 4 tests through an explicitly resolved Vintage engine", async () => {
    const project = await copyFixture(root, work, "junit-vintage");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTextFile(join(project, "zolt.lock"), {
      contains: ["junit:junit", "org.junit.vintage:junit-vintage-engine"],
    });
    await expectTestsFound(t, zolt, 1, [
      "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expect.file(await singleJar(join(project, "target"))).toExist();
  });
});
