import { expect, smoke, type SmokeContext } from "smoque";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";

import { packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("quality failure guidance smoke", { tags: ["quality", "diagnostics"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-quality-failures");
  const zolt = await packagedZolt(t);

  await t.step("missing generated sources report the root and next action", async () => {
    const project = work.path("generated-source-failure");
    await mkdir(join(project, "src/main/openapi"), { recursive: true });
    await writeFile(join(project, "src/main/openapi/api.yaml"), [
      "openapi: 3.1.0",
      "info:",
      "  title: Missing Generated Source Fixture",
      "  version: 0.1.0",
      "",
    ].join("\n"), "utf8");
    await writeFile(join(project, "zolt.toml"), [
      "[project]",
      'name = "generated-source-failure"',
      'version = "0.1.0"',
      'group = "com.example"',
      'java = "21"',
      "",
      "[generated.main.openapi]",
      'kind = "declared-root"',
      'language = "java"',
      'output = "target/generated/sources/openapi"',
      'inputs = ["src/main/openapi/api.yaml"]',
      "required = true",
      "",
    ].join("\n"), "utf8");

    const result = await runZolt(t, zolt, [
      "--no-progress", "check", "--cwd", project, "--cache-root", zolt.cacheRoot,
      "--check", "generated-sources",
    ], { check: false });
    expect.value(result.exitCode === 0).toBeFalsy();
    const output = `${result.stdout}\n${result.stderr}`;
    expect.value(output).toContain("Generated source root `target/generated/sources/openapi` is missing.");
    expect.value(output).toContain("Run the generator that produces it, commit the generated sources");
  });
});
