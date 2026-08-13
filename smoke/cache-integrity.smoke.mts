import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

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
    const corrupted = await corruptMavenArtifact(cache, join(app, "zolt.lock"), {
      group: "com.google.guava",
      artifact: "guava",
      version: "33.4.0-jre",
    });
    await expect.file(corrupted).toExist();

    await expectCommandFailureContains(
      t,
      zolt,
      ["--no-progress", "build", "--offline", "--cwd", app, "--cache-root", cache],
      "Offline mode found corrupt cached JAR for com.google.guava:guava:33.4.0-jre",
    );
  });
});
