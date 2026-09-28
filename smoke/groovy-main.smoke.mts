import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { copyFixture, expectTextFile, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("Groovy main joint compilation smoke", { tags: ["examples", "groovy"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-groovy-main");
  const zolt = await packagedZolt(t);

  await t.step("builds and runs circular Java and Groovy main sources", async () => {
    const project = await copyFixture(root, work, "groovy-main");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTextFile(join(project, "zolt.lock"), {
      contains: ["org.apache.groovy:groovy", "4.0.22"],
    });
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expect.file(join(project, "target/classes/com/example/JavaGreeting.class")).toExist();
    await expect.file(join(project, "target/classes/com/example/GroovyGreeting.class")).toExist();

    const result = await runZolt(t, zolt, [
      "--no-progress", "run", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ]);
    expect.value(result.stdout).toContain("Hello from joint Java/Groovy compilation");
  });
});
