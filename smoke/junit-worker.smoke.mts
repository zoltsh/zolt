import { expect, smoke, type SmokeContext } from "smoque";
import { writeFile } from "node:fs/promises";
import { join } from "node:path";

import { copyFixture, expectTestsFound, packagedZolt, runZolt } from "./support/zolt-smoke.mts";

smoke.suite("zolt JUnit worker smoke", { tags: ["jvm", "junit"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-junit-smoke");
  const zolt = await packagedZolt(t);

  await t.step("JUnit worker selectors run through the packaged worker", async () => {
    const project = await copyFixture(root, work, "junit-basic");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTestsFound(t, zolt, 2, [
      "--no-progress",
      "test",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
      "--test",
      "com.example.MainTest",
    ]);
    await expectTestsFound(t, zolt, 1, [
      "--no-progress",
      "test",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
      "--test",
      "com.example.MainTest#addsNumbers",
    ]);
    await expectTestsFound(t, zolt, 1, [
      "--no-progress",
      "test",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
      "--tests",
      "*GreetingTest",
    ]);
    await expectTestsFound(t, zolt, 2, [
      "--no-progress",
      "test",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
      "--include-tag",
      "fast",
    ]);
    await expectTestsFound(t, zolt, 3, [
      "--no-progress",
      "test",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
      "--exclude-tag",
      "slow",
    ]);
  });

  await t.step("lifecycle container failure reaches the CLI exit status", async () => {
    const project = await copyFixture(root, work, "junit-basic");
    const lifecycleTest = join(project, "src/test/java/com/example/LifecycleFailureTest.java");
    await writeFile(lifecycleTest, `
package com.example;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

final class LifecycleFailureTest {
    @Test
    void passes() {
    }

    @AfterAll
    static void brokenTeardown() {
        throw new IllegalStateException("teardown failed");
    }
}
`, "utf8");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    const result = await runZolt(t, zolt, [
      "--no-progress",
      "test",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
      "--test",
      "com.example.LifecycleFailureTest",
    ], { check: false });

    expect.value(result.exitCode).toBe(1);
    expect.value(`${result.stdout}\n${result.stderr}`).toContain("teardown failed");
  });
});
