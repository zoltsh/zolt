import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { copyFixture, expectTestsFound, expectTextFile, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("Spock test engine smoke", { tags: ["examples", "spock"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-spock");
  const zolt = await packagedZolt(t);

  await t.step("resolves Groovy and runs a Spock spec through JUnit Platform", async () => {
    const project = await copyFixture(root, work, "spock-basic");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTextFile(join(project, "zolt.lock"), {
      contains: ["org.spockframework:spock-core", "org.apache.groovy:groovy"],
    });
    await expectTestsFound(t, zolt, 1, [
      "--no-progress",
      "test",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
    ]);
    await expect.file(join(project, "target/test-classes/com/example/CalculatorSpec.class")).toExist();
  });
});
