import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { expectJarManifest } from "./support/jar.mts";
import { LIBRARY_CANARY_CONTRACTS } from "./support/library-canary-contracts.mts";
import { copyFixture, expectTestsFound, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("library packaging canaries smoke", { tags: ["libraries", "packaging"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-library-canaries");
  const zolt = await packagedZolt(t);

  for (const canary of LIBRARY_CANARY_CONTRACTS) {
    await t.step(`${canary.fixture} emits complete library artifacts`, async () => {
      const project = await copyFixture(root, work, canary.fixture);
      await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
      await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
      await expectTestsFound(t, zolt, 3, [
        "--no-progress", "test", "--cwd", project, "--cache-root", zolt.cacheRoot,
        "--test", canary.testClass, "--jvm-arg", canary.testProperty,
      ]);
      await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);

      const target = join(project, "target");
      const stem = `${canary.fixture}-0.1.0`;
      const mainJar = join(target, `${stem}.jar`);
      await expect.archive(mainJar).toContainEntries([`${canary.className}.class`]);
      await expect.archive(join(target, `${stem}-sources.jar`)).toContainEntries([`${canary.className}.java`]);
      await expect.archive(join(target, `${stem}-javadoc.jar`)).toContainEntries([`${canary.className}.html`]);
      await expect.archive(join(target, `${stem}-tests.jar`)).toContainEntries([
        `${canary.testClass.replaceAll(".", "/")}.class`,
      ]);
      await expectJarManifest(t, mainJar, work.path(`${canary.fixture}-manifest`), [
        `Automatic-Module-Name: ${canary.packageName}`,
      ]);
    });
  }
});
