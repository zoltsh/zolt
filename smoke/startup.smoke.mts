import { expect, smoke, type SmokeContext } from "smoque";
import { mkdir } from "node:fs/promises";

import { expectCommandFailureContains, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("zolt CLI startup smoke", { tags: ["jvm", "cli"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-cli-startup");
  const zolt = await packagedZolt(t);

  await t.step("built CLI starts outside a project", async () => {
    const version = await runZolt(t, zolt, ["--no-progress", "--version"], { timeout: "30s" });
    expect.value(version.stdout.trim()).toBe(process.env.ZOLT_VERSION_OVERRIDE ?? "1.0.0");

    const help = await runZolt(t, zolt, ["--no-progress"], { timeout: "30s" });
    expect.value(help.stdout).toContain("Usage: zolt");
  });

  await t.step("missing config failure names the expected zolt.toml", async () => {
    const missingConfig = work.path("missing-config");
    await mkdir(missingConfig, { recursive: true });

    await expectCommandFailureContains(
      t,
      zolt,
      ["--no-progress", "build", "--cwd", missingConfig, "--cache-root", zolt.cacheRoot],
      `Could not read zolt.toml at ${missingConfig}/zolt.toml`,
    );
  });
});
