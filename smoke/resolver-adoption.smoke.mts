import { smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { RESOLVER_FIXTURE_CONTRACTS } from "./support/resolver-contracts.mts";
import { copyFixture, expectTextFile, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("resolver adoption contracts smoke", { tags: ["resolver", "adoption"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-resolver-adoption");
  const zolt = await packagedZolt(t);

  for (const fixture of RESOLVER_FIXTURE_CONTRACTS) {
    await t.step(`${fixture.name} resolves the expected lockfile contract`, async () => {
      const project = await copyFixture(root, work, fixture.name);
      await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
      await expectTextFile(join(project, "zolt.lock"), fixture.lockfile);
    });
  }
});
