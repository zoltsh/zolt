import { expect, smoke, type SmokeContext } from "smoque";

import { copyFixture, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("workspace quality canary smoke", { tags: ["quality", "workspace"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-quality-workspace");
  const zolt = await packagedZolt(t);

  await t.step("SLF4J workspace validates dependency and package metadata", async () => {
    const workspace = await copyFixture(root, work, "slf4j-canary");
    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--workspace", "--cwd", workspace, "--cache-root", zolt.cacheRoot,
    ]);

    const result = await runZolt(t, zolt, [
      "--no-progress", "check", "--workspace", "--cwd", workspace, "--cache-root", zolt.cacheRoot,
      "--check", "lockfile",
      "--check", "project-model",
      "--check", "dependency-metadata",
      "--check", "package-metadata",
      "--check", "manifest-metadata",
      "--check", "generated-sources",
    ]);
    expect.value(result.stdout).toContain("Workspace zolt.lock matches the workspace config and member zolt.toml files.");
    expect.value(result.stdout).toContain("ok dependency-metadata slf4j-simple org.slf4j:slf4j-api");
    expect.value(result.stdout).toContain("ok package-metadata slf4j-api slf4j-api Library package metadata is complete.");
    expect.value(result.stdout).toContain("ok manifest-metadata slf4j-simple slf4j-simple Library manifest metadata is deterministic.");
    expect.value(result.stdout).toContain("ok generated-sources slf4j-simple slf4j-simple");
  });
});
