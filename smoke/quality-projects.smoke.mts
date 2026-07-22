import { expect, smoke, type SmokeContext } from "smoque";

import { jsonString, parseJsonObject } from "./support/json.mts";
import { BASE_QUALITY_CHECK_ARGUMENTS } from "./support/quality-contracts.mts";
import { copyFixture, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("project quality canaries smoke", { tags: ["quality", "canaries"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-quality-projects");
  const zolt = await packagedZolt(t);

  await t.step("Commons CLI library metadata checks pass", async () => {
    const project = await copyFixture(root, work, "commons-cli-canary");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);

    const result = await runZolt(t, zolt, [
      "--no-progress", "check", "--cwd", project, "--cache-root", zolt.cacheRoot, ...BASE_QUALITY_CHECK_ARGUMENTS,
    ]);
    expect.value(result.stdout).toContain("ok lockfile zolt.lock zolt.lock matches zolt.toml.");
    expect.value(result.stdout).toContain("ok package-metadata commons-cli-canary Library package metadata is complete.");
    expect.value(result.stdout).toContain("ok manifest-metadata commons-cli-canary Library manifest metadata is deterministic.");
    expect.value(result.stdout).toContain("No declared generated-source steps require validation.");
  });

  await t.step("HikariCP dependency metadata is valid in human and JSON output", async () => {
    const project = await copyFixture(root, work, "hikaricp-canary");
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);

    const result = await runZolt(t, zolt, [
      "--no-progress", "check", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--check", "dependency-metadata", ...BASE_QUALITY_CHECK_ARGUMENTS,
    ]);
    expect.value(result.stdout).toContain("ok dependency-metadata org.slf4j:slf4j-api");
    expect.value(result.stdout).toContain("ok dependency-metadata org.checkerframework:checker-qual");
    expect.value(result.stdout).toContain("ok package-metadata hikaricp-canary Library package metadata is complete.");

    const json = await runZolt(t, zolt, [
      "--no-progress", "check", "--format", "json", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--check", "dependency-metadata", "--check", "package-metadata", "--check", "manifest-metadata",
    ]);
    const output = parseJsonObject(t, json.stdout, "quality check JSON output");
    expect.value(jsonString(t, output, "status", "quality check JSON output")).toBe("ok");
    expect.value(JSON.stringify(output.checks)).toContain("dependency-metadata");
    expect.value(JSON.stringify(output.checks)).toContain("package-metadata");
    expect.value(JSON.stringify(output.checks)).toContain("manifest-metadata");
  });
});
