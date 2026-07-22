import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { classFileMajor, writeJavaProject } from "./support/java-project.mts";
import { jsonString, packagedZolt, parseJsonObject, runZolt } from "./support/zolt-smoke.mts";
import { expectJsonObject } from "./support/json.mts";

smoke.suite("Java release matrix smoke", { tags: ["compiler", "java-release"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-java-release-matrix");
  const zolt = await packagedZolt(t);

  const status = await runZolt(t, zolt, ["--no-progress", "toolchain", "status", "--json"]);
  const toolchain = parseJsonObject(t, status.stdout, "toolchain status");
  const resolved = expectJsonObject(t, toolchain.resolved, "toolchain status.resolved");
  const currentJava = Number.parseInt(jsonString(t, resolved, "version", "toolchain status.resolved"), 10);
  if (!Number.isInteger(currentJava) || currentJava < 8) {
    t.fail(`Expected a resolved Java 8+ toolchain, received ${status.stdout}`);
  }

  await t.step(`emits Java 8 through ${currentJava} class-file versions`, async () => {
    for (let release = 8; release <= currentJava; release += 1) {
      const project = work.path(`release-${release}`);
      await writeJavaProject(project, {
        java: release,
        name: `java-release-${release}`,
        source: [
          "package com.example;",
          "",
          "public final class Main {",
          "    private Main() {}",
          `    public static void main(String[] args) { System.out.println("release-${release}"); }`,
          "}",
          "",
        ].join("\n"),
      });
      await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
      expect.value(await classFileMajor(join(project, "target/classes/com/example/Main.class"))).toBe(release + 44);
    }
  });

  await t.step("Java 8 rejects APIs introduced by newer releases", async () => {
    const project = work.path("java-8-api-guard");
    await writeJavaProject(project, {
      java: 8,
      name: "java-8-api-guard",
      source: [
        "package com.example;",
        "",
        "public final class Main {",
        "    private Main() {}",
        '    public static void main(String[] args) { System.out.println(" ".isBlank()); }',
        "}",
        "",
      ].join("\n"),
    });
    const result = await runZolt(t, zolt, [
      "--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot,
    ], { check: false });
    expect.value(result.exitCode === 0).toBeFalsy();
    expect.value(`${result.stdout}\n${result.stderr}`).toContain("isBlank");
  });
});
