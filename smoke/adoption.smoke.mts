import { expect, smoke, type SmokeContext } from "smoque";
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

import {
  copyFixture,
  expectCommandFailureContains,
  packagedZolt,
  runZolt,
  singleJar,
} from "./support/zolt-smoke.mts";

smoke.suite("zolt adoption app smoke", { tags: ["jvm", "adoption"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-adoption-smoke");
  const zolt = await packagedZolt(t);

  await t.step("plain app resolves, tests, packages, and runs", async () => {
    const app = await copyFixture(root, work, "adoption-plain-app");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", app, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "test", "--cwd", app, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", app, "--cache-root", zolt.cacheRoot]);
    const packagedApp = await singleJar(join(app, "target"));
    await expect.file(packagedApp).toExist();

    const result = await runZolt(t, zolt, [
      "--no-progress",
      "run-package",
      "--cwd",
      app,
      "--cache-root",
      zolt.cacheRoot,
      "--",
      "adoption",
    ]);
    expect.value(result.stdout).toContain("Hello from adoption!");
  });

  await t.step("stale lockfile failure explains changed repository inputs", async () => {
    const app = await copyFixture(root, work, "adoption-plain-app", "adoption-plain-app-stale");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", app, "--cache-root", zolt.cacheRoot]);
    const toml = join(app, "zolt.toml");
    const original = await readFile(toml, "utf8");
    await writeFile(
      toml,
      original + '\n[repositories.staleness-probe]\nurl = "https://repo.maven.apache.org/maven2/./"\n',
      "utf8",
    );

    await expectCommandFailureContains(
      t,
      zolt,
      ["--no-progress", "build", "--cwd", app, "--cache-root", zolt.cacheRoot],
      "Changed inputs: repositories.",
    );
  });
});
