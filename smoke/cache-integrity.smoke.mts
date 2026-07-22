import { expect, smoke, type SmokeContext } from "smoque";

import { expectCommandFailureContains } from "./support/assertions.mts";
import { copyFixture } from "./support/fixtures.mts";
import { corruptMavenArtifact } from "./support/maven-cache.mts";
import { packagedZolt, runZolt } from "./support/zolt-runtime.mts";

smoke.suite("dependency cache integrity smoke", { tags: ["resolver", "integrity"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-cache-integrity");
  const cache = work.path("cache");
  const app = await copyFixture(root, work, "adoption-plain-app");
  const zolt = await packagedZolt(t);

  await t.step("rejects a cached dependency whose bytes no longer match the lockfile", async () => {
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", app, "--cache-root", cache]);
    const corrupted = await corruptMavenArtifact(cache, {
      group: "com.google.guava",
      artifact: "guava",
      version: "33.4.0-jre",
    });
    await expect.file(corrupted).toExist();

    await expectCommandFailureContains(
      t,
      zolt,
      ["--no-progress", "build", "--cwd", app, "--cache-root", cache],
      "Cached jar integrity check failed for com.google.guava:guava:33.4.0-jre",
    );
  });
});
